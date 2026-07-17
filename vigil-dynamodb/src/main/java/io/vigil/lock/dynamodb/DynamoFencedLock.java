package io.vigil.lock.dynamodb;

import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DynamoFencedLock implements FencedLock {

    private final DynamoDbClient ddb;
    private final String         table;

    public DynamoFencedLock(DynamoDbClient ddb) {
        this(ddb, "vigil_job_locks");
    }

    public DynamoFencedLock(DynamoDbClient ddb, String table) {
        this.ddb   = ddb;
        this.table = table;
    }

    @Override
    public void ensureSeedRow(String jobName) {
        long now = Instant.now().toEpochMilli();
        Map<String, AttributeValue> seed = new HashMap<>();
        seed.put("job_name", s(jobName));
        seed.put("run_id", s(UUID.randomUUID().toString()));
        seed.put("holder", s("init"));
        seed.put("token", n(0));
        seed.put("acquired_at", n(now));
        seed.put("expires_at", n(now));
        seed.put("status", s("FREE"));
        try {
            ddb.putItem(b -> b.tableName(table).item(seed)
                    .conditionExpression("attribute_not_exists(job_name)"));
        } catch (ConditionalCheckFailedException ignored) {
        }
    }

    @Override
    public Optional<LockAcquisition> tryAcquire(String jobName, String podId, Duration ttl) {
        long now     = Instant.now().toEpochMilli();
        long expires = now + ttl.toMillis();

        Map<String, AttributeValue> item = ddb.getItem(b -> b.tableName(table)
                .key(Map.of("job_name", s(jobName)))
                .consistentRead(true)).item();

        boolean present = item != null && !item.isEmpty();
        long   existingToken = 0;
        String existingRunId = null;
        if (present) {
            String status  = item.get("status").s();
            long   expAt    = Long.parseLong(item.get("expires_at").n());
            existingToken   = Long.parseLong(item.get("token").n());
            existingRunId   = item.containsKey("run_id") ? item.get("run_id").s() : null;

            boolean acquirable = !"PAUSED".equals(status)
                    && ("FREE".equals(status) || "ORPHANED".equals(status) || expAt < now);
            if (!acquirable) {
                return Optional.empty();
            }
        }

        long   newToken = present ? existingToken + 1 : 1;
        long   expAt    = present ? Long.parseLong(item.get("expires_at").n()) : 0;
        boolean fresh   = !present
                || existingRunId == null
                || ("FREE".equals(item.get("status").s()) && expAt >= now);
        String runId    = fresh ? UUID.randomUUID().toString() : existingRunId;

        Map<String, AttributeValue> newItem = new HashMap<>();
        newItem.put("job_name", s(jobName));
        newItem.put("run_id", s(runId));
        newItem.put("holder", s(podId));
        newItem.put("token", n(newToken));
        newItem.put("acquired_at", n(now));
        newItem.put("expires_at", n(expires));
        newItem.put("status", s("HELD"));

        try {
            if (present) {
                long expected = existingToken;
                ddb.putItem(b -> b.tableName(table).item(newItem)
                        .conditionExpression(
                                "#t = :expected AND #s <> :paused "
                                        + "AND (#s = :free OR #s = :orphaned OR expires_at < :now)")
                        .expressionAttributeNames(Map.of("#t", "token", "#s", "status"))
                        .expressionAttributeValues(Map.of(
                                ":expected", n(expected),
                                ":paused", s("PAUSED"),
                                ":free", s("FREE"),
                                ":orphaned", s("ORPHANED"),
                                ":now", n(now))));
            } else {
                ddb.putItem(b -> b.tableName(table).item(newItem)
                        .conditionExpression("attribute_not_exists(job_name)"));
            }
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }

        return Optional.of(new LockAcquisition(newToken, UUID.fromString(runId)));
    }

    @Override
    public boolean tryRenew(String jobName, long fencingToken, Duration ttl) {
        long expires = Instant.now().toEpochMilli() + ttl.toMillis();
        try {
            ddb.updateItem(b -> b.tableName(table)
                    .key(Map.of("job_name", s(jobName)))
                    .updateExpression("SET expires_at = :exp")
                    .conditionExpression("#t = :token AND #s = :held")
                    .expressionAttributeNames(Map.of("#t", "token", "#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":exp", n(expires),
                            ":token", n(fencingToken),
                            ":held", s("HELD"))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public void release(String jobName, long fencingToken) {
        try {
            ddb.updateItem(b -> b.tableName(table)
                    .key(Map.of("job_name", s(jobName)))
                    .updateExpression("SET #s = :free")
                    .conditionExpression("#t = :token AND #s = :held")
                    .expressionAttributeNames(Map.of("#t", "token", "#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":token", n(fencingToken),
                            ":free", s("FREE"),
                            ":held", s("HELD"))));
        } catch (ConditionalCheckFailedException ignored) {
        }
    }

    @Override
    public void checkConnectivity() {
        ddb.describeTable(b -> b.tableName(table));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }
}
