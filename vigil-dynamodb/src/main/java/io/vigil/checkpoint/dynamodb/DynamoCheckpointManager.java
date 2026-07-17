package io.vigil.checkpoint.dynamodb;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.spi.CheckpointManager;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class DynamoCheckpointManager implements CheckpointManager {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient ddb;
    private final String         lockTable;
    private final String         checkpointTable;

    public DynamoCheckpointManager(DynamoDbClient ddb) {
        this(ddb, "vigil_job_locks", "vigil_job_checkpoints");
    }

    public DynamoCheckpointManager(DynamoDbClient ddb, String lockTable, String checkpointTable) {
        this.ddb             = ddb;
        this.lockTable       = lockTable;
        this.checkpointTable = checkpointTable;
    }

    @Override
    public void save(CheckpointEntry entry) {
        Map<String, AttributeValue> ckptItem = new HashMap<>();
        ckptItem.put("job_name", s(entry.jobName()));
        ckptItem.put("ckpt_key", s(sortKey(entry.runId(), entry.stageName())));
        ckptItem.put("run_id", s(entry.runId().toString()));
        ckptItem.put("stage_name", s(entry.stageName()));
        ckptItem.put("status", s(entry.status().name()));
        ckptItem.put("fencing_token", n(entry.fencingToken()));
        ckptItem.put("updated_at", n(Instant.now().toEpochMilli()));
        if (entry.storedValue() != null) {
            ckptItem.put("stored_value", s(entry.storedValue()));
        }
        if (entry.valueType() != null) {
            ckptItem.put("value_type", s(entry.valueType()));
        }

        ConditionCheck lockCheck = ConditionCheck.builder()
                .tableName(lockTable)
                .key(Map.of("job_name", s(entry.jobName())))
                .conditionExpression("#t = :token")
                .expressionAttributeNames(Map.of("#t", "token"))
                .expressionAttributeValues(Map.of(":token", n(entry.fencingToken())))
                .build();

        Put put = Put.builder()
                .tableName(checkpointTable)
                .item(ckptItem)
                .conditionExpression("attribute_not_exists(job_name) OR fencing_token <= :token")
                .expressionAttributeValues(Map.of(":token", n(entry.fencingToken())))
                .build();

        try {
            ddb.transactWriteItems(b -> b.transactItems(
                    TransactWriteItem.builder().conditionCheck(lockCheck).build(),
                    TransactWriteItem.builder().put(put).build()));
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            if (!reasons.isEmpty() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(0).code())) {
                throw new LockStolenException(
                        "Fencing token " + entry.fencingToken() + " is stale for job " + entry.jobName());
            }
            if (reasons.size() > 1 && CONDITIONAL_CHECK_FAILED.equals(reasons.get(1).code())) {
                return;
            }
            throw e;
        }
    }

    @Override
    public void clearRun(String jobName, UUID runId, long fencingToken) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        ddb.queryPaginator(b -> b.tableName(checkpointTable)
                        .keyConditionExpression("job_name = :j AND begins_with(ckpt_key, :p)")
                        .expressionAttributeValues(Map.of(
                                ":j", s(jobName),
                                ":p", s(runId + "#")))
                        .projectionExpression("job_name, ckpt_key")
                        .consistentRead(true))
                .items()
                .forEach(item -> keys.add(Map.of(
                        "job_name", item.get("job_name"),
                        "ckpt_key", item.get("ckpt_key"))));
        if (keys.isEmpty()) {
            return;
        }

        ConditionCheck lockCheck = ConditionCheck.builder()
                .tableName(lockTable)
                .key(Map.of("job_name", s(jobName)))
                .conditionExpression("#t = :token")
                .expressionAttributeNames(Map.of("#t", "token"))
                .expressionAttributeValues(Map.of(":token", n(fencingToken)))
                .build();

        for (int i = 0; i < keys.size(); i += 99) {
            List<Map<String, AttributeValue>> chunk = keys.subList(i, Math.min(i + 99, keys.size()));
            List<TransactWriteItem> items = new ArrayList<>();
            items.add(TransactWriteItem.builder().conditionCheck(lockCheck).build());
            for (Map<String, AttributeValue> key : chunk) {
                items.add(TransactWriteItem.builder()
                        .delete(Delete.builder().tableName(checkpointTable).key(key).build())
                        .build());
            }
            try {
                ddb.transactWriteItems(b -> b.transactItems(items));
            } catch (TransactionCanceledException e) {
                List<CancellationReason> reasons = e.cancellationReasons();
                if (!reasons.isEmpty() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(0).code())) {
                    return;
                }
                throw e;
            }
        }
    }

    @Override
    public Optional<CheckpointEntry> load(String jobName, UUID runId, String stageName) {
        Map<String, AttributeValue> item = ddb.getItem(b -> b.tableName(checkpointTable)
                .key(Map.of(
                        "job_name", s(jobName),
                        "ckpt_key", s(sortKey(runId, stageName))))
                .consistentRead(true)).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(item));
    }

    @Override
    public boolean isComplete(String jobName, UUID runId, String stageName) {
        Map<String, AttributeValue> item = ddb.getItem(b -> b.tableName(checkpointTable)
                .key(Map.of(
                        "job_name", s(jobName),
                        "ckpt_key", s(sortKey(runId, stageName))))
                .consistentRead(true)).item();
        return item != null && item.containsKey("status")
                && CheckpointStatus.COMPLETE.name().equals(item.get("status").s());
    }

    @Override
    public List<String> listStageNames(String jobName) {
        Set<String> stages = new LinkedHashSet<>();
        ddb.queryPaginator(b -> b.tableName(checkpointTable)
                        .keyConditionExpression("job_name = :j")
                        .expressionAttributeValues(Map.of(":j", s(jobName)))
                        .consistentRead(true))
                .items()
                .forEach(item -> {
                    if (item.containsKey("stage_name")) {
                        stages.add(item.get("stage_name").s());
                    }
                });
        return new ArrayList<>(stages);
    }

    @Override
    public boolean hasAnyCheckpoint(String jobName, UUID runId) {
        return ddb.query(b -> b.tableName(checkpointTable)
                        .keyConditionExpression("job_name = :j AND begins_with(ckpt_key, :p)")
                        .expressionAttributeValues(Map.of(
                                ":j", s(jobName),
                                ":p", s(runId + "#")))
                        .consistentRead(true)
                        .select("COUNT"))
                .count() > 0;
    }

    private static CheckpointEntry fromItem(Map<String, AttributeValue> item) {
        return new CheckpointEntry(
                item.get("job_name").s(),
                UUID.fromString(item.get("run_id").s()),
                item.get("stage_name").s(),
                CheckpointStatus.valueOf(item.get("status").s()),
                item.containsKey("stored_value") ? item.get("stored_value").s() : null,
                item.containsKey("value_type") ? item.get("value_type").s() : null,
                Long.parseLong(item.get("fencing_token").n()));
    }

    private static String sortKey(UUID runId, String stageName) {
        return runId + "#" + stageName;
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }
}
