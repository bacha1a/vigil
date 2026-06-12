package io.vigil.scheduler;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

@Testcontainers
class GcPauseChaosTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate          jdbc;
    static JdbcFencedLock        lock;
    static JdbcCheckpointManager checkpoints;

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
    }

    @Nested
    @DisplayName("100-iteration GC pause chaos")
    class HundredIterations {

        @Test
        @DisplayName("fencing token prevents zombie checkpoint writes across 100 GC pause iterations")
        void zombieWritesRejectedAcross100Iterations() throws InterruptedException {
            int corruptionEvents = 0;

            for (int i = 0; i < 100; i++) {
                jdbc.update("DELETE FROM vigil_job_checkpoints WHERE job_name = 'chaos-job'");
                jdbc.update("DELETE FROM vigil_job_locks WHERE job_name = 'chaos-job'");
                var now = Timestamp.from(Instant.now());
                jdbc.update(
                        "INSERT INTO vigil_job_locks (job_name, run_id, holder, token, acquired_at, expires_at, status) " +
                        "VALUES ('chaos-job', '00000000-0000-0000-0000-000000000000', 'init', 0, ?, ?, 'FREE')",
                        now, now);

                var pod1Acq = lock.tryAcquire("chaos-job", "pod-1", Duration.ofSeconds(5));
                assertThat(pod1Acq).as("pod-1 should acquire on iteration " + i).isPresent();
                var tokenN = pod1Acq.get().fencingToken();

                SimulatedGCPause.pause(Duration.ofSeconds(7));

                var pod2Acq = lock.tryAcquire("chaos-job", "pod-2", Duration.ofSeconds(30));
                assertThat(pod2Acq).as("pod-2 should acquire after lock expires on iteration " + i).isPresent();

                var zombieEntry = new CheckpointEntry(
                        "chaos-job", pod1Acq.get().runId(), "stage-1",
                        CheckpointStatus.IN_PROGRESS, "zombie-value", "java.lang.String", tokenN);

                try {
                    checkpoints.save(zombieEntry);
                    corruptionEvents++;
                } catch (LockStolenException ignored) {
                }

                var staleRows = jdbc.queryForList(
                        "SELECT * FROM vigil_job_checkpoints WHERE job_name = 'chaos-job' AND fencing_token = ?",
                        tokenN);
                assertThat(staleRows).as("no checkpoint rows with stale token on iteration " + i).isEmpty();

                var validEntry = new CheckpointEntry(
                        "chaos-job", pod2Acq.get().runId(), "stage-1",
                        CheckpointStatus.IN_PROGRESS, "pod2-value", "java.lang.String",
                        pod2Acq.get().fencingToken());
                assertThatNoException()
                        .as("pod-2 should write checkpoint freely on iteration " + i)
                        .isThrownBy(() -> checkpoints.save(validEntry));
            }

            assertThat(corruptionEvents)
                    .as("zero corruption events: fencing must reject all zombie writes")
                    .isEqualTo(0);

            int totalRounds = 100;
            String json = String.format(
                    "{%n" +
                    "  \"totalRounds\": %d,%n" +
                    "  \"corruptionEvents\": %d,%n" +
                    "  \"corruptionRate\": \"%d/%d\",%n" +
                    "  \"conclusion\": \"Vigil fencing tokens reject every zombie checkpoint write\"%n" +
                    "}%n",
                    totalRounds, corruptionEvents, corruptionEvents, totalRounds);

            try {
                Path targetDir = Path.of("target");
                Files.createDirectories(targetDir);
                Files.writeString(targetDir.resolve("vigil-gc-chaos-results.json"), json);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("[Vigil GC Chaos] " + json);
        }
    }
}
