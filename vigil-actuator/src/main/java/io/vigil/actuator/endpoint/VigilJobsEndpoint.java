package io.vigil.actuator.endpoint;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vigil.actuator.model.JobStatusView;
import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Endpoint(id = "vigil-jobs")
public class VigilJobsEndpoint {

    private final JdbcTemplate            jdbc;
    private final FencedJobWorkerRegistry registry;
    private final MeterRegistry           meterRegistry;
    private final String                  sqlJobs;
    private final String                  sqlPause;
    private final String                  sqlResume;
    private final String                  sqlClearLock;
    private final String                  sqlClearCheckpoint;

    public VigilJobsEndpoint(JdbcTemplate jdbc, FencedJobWorkerRegistry registry,
                             @Nullable MeterRegistry meterRegistry) {
        this.jdbc               = jdbc;
        this.registry           = registry;
        this.meterRegistry      = meterRegistry;
        this.sqlJobs            = loadSql("jobs.sql");
        this.sqlPause           = loadSql("pause.sql");
        this.sqlResume          = loadSql("resume.sql");
        this.sqlClearLock       = loadSql("clear-lock.sql");
        this.sqlClearCheckpoint = loadSql("clear-checkpoint.sql");
    }

    @ReadOperation
    public List<JobStatusView> jobs() {
        return jdbc.queryForList(sqlJobs).stream().map(this::toView).toList();
    }

    @WriteOperation
    public void act(@Selector String name, String action) {
        switch (action) {
            case "trigger" -> registry.submitRecovery(name);
            case "pause"   -> jdbc.update(sqlPause, name);
            case "resume"  -> { jdbc.update(sqlResume, name); registry.submitRecovery(name); }
            default        -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    @DeleteOperation
    public void clear(@Selector String name, @Selector String confirm, @Selector String resource) {
        if (!"true".equals(confirm)) return;
        switch (resource) {
            case "lock"       -> jdbc.update(sqlClearLock, UUID.randomUUID().toString(), name);
            case "checkpoint" -> jdbc.update(sqlClearCheckpoint, name);
            default           -> throw new IllegalArgumentException("Unknown resource: " + resource);
        }
    }

    private JobStatusView toView(Map<String, Object> row) {
        var checkpointTs = (Timestamp) row.get("updated_at");
        var startedAt    = (Timestamp) row.get("started_at");
        var finishedAt   = (Timestamp) row.get("finished_at");
        Long durationMs  = (startedAt != null && finishedAt != null)
                ? finishedAt.getTime() - startedAt.getTime()
                : null;
        var jobName = (String) row.get("job_name");
        return new JobStatusView(
                jobName,
                (String) row.get("status"),
                (String) row.get("holder"),
                (Long)   row.get("token"),
                (String) row.get("run_id"),
                (String) row.get("stage_name"),
                checkpointTs != null ? checkpointTs.toInstant() : null,
                registry.nextTriggerAt(jobName).orElse(null),
                durationMs,
                (Long)   row.get("items_processed"),
                (String) row.get("error_message"),
                checkpointSaveCount(jobName),
                failoverRecoveryCount(jobName)
        );
    }

    private Long failoverRecoveryCount(String jobName) {
        if (meterRegistry == null) return null;
        Counter c = meterRegistry.find("vigil.failover.recoveries.total")
                .tag("job", jobName)
                .counter();
        return c == null ? 0L : (long) c.count();
    }

    private Long checkpointSaveCount(String jobName) {
        if (meterRegistry == null) return null;
        Counter c = meterRegistry.find("vigil.checkpoint.saves.total")
                .tag("job", jobName)
                .counter();
        return c == null ? 0L : (long) c.count();
    }

    private static String loadSql(String name) {
        var path = "/io/vigil/actuator/" + name;
        try (InputStream is = VigilJobsEndpoint.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("SQL file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SQL: " + name, e);
        }
    }
}
