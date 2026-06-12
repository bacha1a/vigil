package io.vigil.scheduler;

import io.vigil.scheduler.history.VigilJobRunRecorder;

import io.vigil.checkpoint.jdbc.JdbcCheckpointManager;
import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.lock.jdbc.JdbcFencedLock;
import io.vigil.test.SimulatedGCPause;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JobRunHistoryIntegrityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate          jdbc;
    static JdbcFencedLock        lock;
    static JdbcCheckpointManager checkpoints;
    static VigilJobRunRecorder   recorder;

    @BeforeAll
    static void setUp() {
        var ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        lock        = new JdbcFencedLock(jdbc, tx);
        checkpoints = new JdbcCheckpointManager(jdbc, tx);
        recorder    = new VigilJobRunRecorder(jdbc);

        jdbc.execute(
                "CREATE TABLE vigil_job_locks (" +
                "job_name VARCHAR(255) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, " +
                "holder VARCHAR(255) NOT NULL, token BIGINT NOT NULL DEFAULT 0, " +
                "acquired_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, " +
                "status VARCHAR(16) NOT NULL)");

        jdbc.execute(
                "CREATE TABLE vigil_job_checkpoints (" +
                "job_name VARCHAR(255) NOT NULL, run_id VARCHAR(36) NOT NULL, " +
                "stage_name VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL, " +
                "stored_value TEXT, value_type VARCHAR(512), " +
                "fencing_token BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL, " +
                "PRIMARY KEY (job_name, run_id, stage_name))");

        jdbc.execute(
                "CREATE TABLE vigil_job_runs (" +
                "job_name VARCHAR(255) NOT NULL, run_id VARCHAR(36) NOT NULL, " +
                "started_at TIMESTAMP NOT NULL, finished_at TIMESTAMP, " +
                "status VARCHAR(16) NOT NULL, items_processed BIGINT, " +
                "error_message TEXT, " +
                "PRIMARY KEY (job_name, run_id))");
    }

    @Nested
    @DisplayName("job run history integrity under GC pause chaos")
    class HistoryIntegrity {

        @Test
        @DisplayName("SUCCESS status is recorded correctly across 20 GC pause iterations")
        void runHistoryIsCorrectAcross20Iterations() throws InterruptedException {
            for (int i = 0; i < 20; i++) {
                String jobName = "history-job-" + i;
                var now = Timestamp.from(Instant.now());
                jdbc.update(
                        "INSERT INTO vigil_job_locks (job_name, run_id, holder, token, acquired_at, expires_at, status) " +
                        "VALUES (?, '00000000-0000-0000-0000-000000000000', 'init', 0, ?, ?, 'FREE')",
                        jobName, now, now);

                var pod1Acq = lock.tryAcquire(jobName, "pod-1", Duration.ofSeconds(5));
                assertThat(pod1Acq).isPresent();
                var pod1RunId = pod1Acq.get().runId().toString();
                var pod1Token = pod1Acq.get().fencingToken();
                recorder.startRun(jobName, pod1RunId, Instant.now());

                SimulatedGCPause.pause(Duration.ofSeconds(7));

                var pod2Acq = lock.tryAcquire(jobName, "pod-2", Duration.ofSeconds(30));
                assertThat(pod2Acq).isPresent();
                var pod2RunId = pod2Acq.get().runId().toString();
                recorder.startRun(jobName, pod2RunId, Instant.now());

                var zombieEntry = new CheckpointEntry(
                        jobName, pod1Acq.get().runId(), "work",
                        CheckpointStatus.IN_PROGRESS, "v", "String", pod1Token);
                try {
                    checkpoints.save(zombieEntry);
                } catch (LockStolenException e) {
                    recorder.finishRun(jobName, pod1RunId, Instant.now(), "STOLEN", 0, e.getMessage());
                }

                recorder.finishRun(jobName, pod2RunId, Instant.now(), "SUCCESS", 10, null);
                lock.release(jobName, pod2Acq.get().fencingToken());
            }

            List<Map<String, Object>> allRuns = jdbc.queryForList(
                    "SELECT * FROM vigil_job_runs WHERE job_name LIKE 'history-job-%'");

            List<Map<String, Object>> successRuns = allRuns.stream()
                    .filter(r -> "SUCCESS".equals(r.get("status")))
                    .toList();

            assertThat(allRuns).as("every recorded run has finished_at set")
                    .allMatch(r -> r.get("finished_at") != null);

            assertThat(allRuns).as("no run is still RUNNING with a finished_at")
                    .noneMatch(r -> "RUNNING".equals(r.get("status")) && r.get("finished_at") != null);

            assertThat(successRuns).as("every iteration produced a SUCCESS run").hasSize(20);
            assertThat(successRuns).as("every SUCCESS run records 10 items processed")
                    .allMatch(r -> ((Number) r.get("items_processed")).longValue() == 10L);
        }
    }
}
