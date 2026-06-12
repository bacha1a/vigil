package io.vigil.checkpoint.jdbc;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.spi.CheckpointManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class JdbcCheckpointManager implements CheckpointManager {

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final String              sqlLockForUpdate;
    private final String              sqlCheckpointToken;
    private final String              sqlInsert;
    private final String              sqlUpdate;
    private final String              sqlLoad;
    private final String              sqlStatus;
    private final String              sqlStageNames;
    private final String              sqlHasAnyCheckpoint;

    public JdbcCheckpointManager(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc                = jdbc;
        this.tx                  = tx;
        this.sqlLockForUpdate    = loadSql("lock-for-update.sql");
        this.sqlCheckpointToken  = loadSql("checkpoint-token.sql");
        this.sqlInsert           = loadSql("insert.sql");
        this.sqlUpdate           = loadSql("update.sql");
        this.sqlLoad             = loadSql("load.sql");
        this.sqlStatus           = loadSql("status.sql");
        this.sqlStageNames       = loadSql("stage-names.sql");
        this.sqlHasAnyCheckpoint = loadSql("has-any-checkpoint.sql");
    }

    @Override
    public void save(CheckpointEntry entry) {
        tx.executeWithoutResult(status -> {
            guardFencingToken(entry);
            upsert(entry);
        });
    }

    @Override
    public Optional<CheckpointEntry> load(String jobName, UUID runId, String stageName) {
        List<Map<String, Object>> rows = jdbc.queryForList(sqlLoad, jobName, runId.toString(), stageName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.getFirst()));
    }

    @Override
    public boolean isComplete(String jobName, UUID runId, String stageName) {
        List<Map<String, Object>> rows = jdbc.queryForList(sqlStatus, jobName, runId.toString(), stageName);
        return !rows.isEmpty() && CheckpointStatus.COMPLETE.name().equals(rows.getFirst().get("status"));
    }

    @Override
    public List<String> listStageNames(String jobName) {
        return jdbc.queryForList(sqlStageNames, String.class, jobName);
    }

    @Override
    public boolean hasAnyCheckpoint(String jobName, UUID runId) {
        Long count = jdbc.queryForObject(sqlHasAnyCheckpoint, Long.class, jobName, runId.toString());
        return count != null && count > 0L;
    }

    private void guardFencingToken(CheckpointEntry entry) {
        List<Map<String, Object>> lockRows = jdbc.queryForList(sqlLockForUpdate, entry.jobName());
        if (lockRows.isEmpty() || lockToken(lockRows.getFirst()) != entry.fencingToken()) {
            throw new LockStolenException(
                    "Fencing token " + entry.fencingToken() + " is stale for job " + entry.jobName());
        }
    }

    private void upsert(CheckpointEntry entry) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                sqlCheckpointToken, entry.jobName(), entry.runId().toString(), entry.stageName());

        if (existing.isEmpty()) {
            insert(entry);
        } else if (checkpointToken(existing.getFirst()) <= entry.fencingToken()) {
            update(entry);
        }
    }

    private void insert(CheckpointEntry entry) {
        jdbc.update(sqlInsert,
                entry.jobName(), entry.runId().toString(), entry.stageName(),
                entry.status().name(), entry.storedValue(), entry.valueType(),
                entry.fencingToken(), Timestamp.from(Instant.now()));
    }

    private void update(CheckpointEntry entry) {
        jdbc.update(sqlUpdate,
                entry.status().name(), entry.storedValue(), entry.valueType(),
                entry.fencingToken(), Timestamp.from(Instant.now()),
                entry.jobName(), entry.runId().toString(), entry.stageName());
    }

    private static CheckpointEntry mapRow(Map<String, Object> row) {
        return new CheckpointEntry(
                (String) row.get("job_name"),
                UUID.fromString((String) row.get("run_id")),
                (String) row.get("stage_name"),
                CheckpointStatus.valueOf((String) row.get("status")),
                (String) row.get("stored_value"),
                (String) row.get("value_type"),
                checkpointToken(row));
    }

    private static long lockToken(Map<String, Object> row) {
        return ((Number) row.get("token")).longValue();
    }

    private static long checkpointToken(Map<String, Object> row) {
        return ((Number) row.get("fencing_token")).longValue();
    }

    private static String loadSql(String name) {
        String path = "/io/vigil/checkpoint/jdbc/" + name;
        try (InputStream is = JdbcCheckpointManager.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("SQL file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SQL: " + name, e);
        }
    }
}
