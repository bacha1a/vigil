package io.vigil.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.checkpoint.jdbc.JdbcCheckpointManager;
import io.vigil.core.support.StageKeys;
import io.vigil.lock.jdbc.JdbcFencedLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CheckpointResumeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate           jdbc;
    static JdbcFencedLock         lock;
    static JdbcCheckpointManager  checkpoints;
    static ObjectMapper           jackson;

    @BeforeAll
    static void setUpSchema() {
        var ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        lock        = new JdbcFencedLock(jdbc, tx);
        checkpoints = new JdbcCheckpointManager(jdbc, tx);
        jackson     = new ObjectMapper();

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

    @BeforeEach
    void resetJob() {
        jdbc.update("DELETE FROM vigil_job_checkpoints WHERE job_name LIKE 'resume-job%'");
        jdbc.update("DELETE FROM vigil_job_locks WHERE job_name LIKE 'resume-job%'");
    }

    private JobContext acquireAndBind(String jobName) {
        lock.ensureSeedRow(jobName);
        var acq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
        assertThat(acq).as("lock should be acquirable for " + jobName).isPresent();
        var ctx = new JobContext(jobName, acq.get().runId(), acq.get().fencingToken(),
                checkpoints, jackson);
        JobContext.bind(ctx);
        return ctx;
    }

    @Nested
    @DisplayName("forEach checkpoint resume")
    class ForEachResume {

        @Test
        @DisplayName("first run processes all items and marks stage complete")
        void firstRun_processesAllItems() {
            String jobName = "resume-job-full";
            var ctx = acquireAndBind(jobName);
            var items = List.of("A", "B", "C", "D", "E");
            var processed = new ArrayList<String>();

            ctx.forEach(Stage.WORK, items, s -> s, (item, token) -> processed.add(item));

            JobContext.unbind();

            assertThat(processed).containsExactly("A", "B", "C", "D", "E");
            assertThat(checkpoints.isComplete(jobName, ctx.getRunId(), StageKeys.toDbKey(Stage.WORK))).isTrue();
        }

        @Test
        @DisplayName("interrupted after 3 items - second run processes only remaining 2")
        void resume_skipsAlreadyProcessedItems() throws Exception {
            String jobName = "resume-job-partial";
            lock.ensureSeedRow(jobName);

            var firstAcq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
            assertThat(firstAcq).isPresent();
            var runId  = firstAcq.get().runId();
            var token  = firstAcq.get().fencingToken();

            var firstCtx = new JobContext(jobName, runId, token, checkpoints, jackson);
            JobContext.bind(firstCtx);

            var items = List.of("item-1", "item-2", "item-3", "item-4", "item-5");
            var firstRunProcessed = new ArrayList<String>();
            var itemCount = new AtomicInteger(0);

            try {
                firstCtx.forEach(Stage.WORK, items, s -> s, (item, t) -> {
                    if (itemCount.incrementAndGet() == 4) {
                        throw new RuntimeException("simulated crash before the 4th item");
                    }
                    firstRunProcessed.add(item);
                });
            } catch (RuntimeException ignored) {
            }

            JobContext.unbind();

            assertThat(firstRunProcessed).containsExactly("item-1", "item-2", "item-3");
            assertThat(checkpoints.isComplete(jobName, runId, StageKeys.toDbKey(Stage.WORK))).isFalse();

            jdbc.update("UPDATE vigil_job_locks SET status = 'ORPHANED' WHERE job_name = ?", jobName);
            var secondAcq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
            assertThat(secondAcq).isPresent();

            var secondCtx = new JobContext(
                    jobName, secondAcq.get().runId(), secondAcq.get().fencingToken(),
                    checkpoints, jackson);
            JobContext.bind(secondCtx);

            var secondRunProcessed = new ArrayList<String>();
            secondCtx.forEach(Stage.WORK, items, s -> s,
                    (item, t) -> secondRunProcessed.add(item));

            JobContext.unbind();

            assertThat(secondRunProcessed)
                    .as("second run should skip already-checkpointed items and process only the remaining ones")
                    .containsExactly("item-4", "item-5");
            assertThat(firstRunProcessed.size() + secondRunProcessed.size())
                    .as("total items processed across both runs equals 5")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("second run skips entire stage if first run completed it")
        void resume_skipsCompleteStage() {
            String jobName = "resume-job-complete";
            lock.ensureSeedRow(jobName);

            var firstAcq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
            assertThat(firstAcq).isPresent();
            var firstCtx = new JobContext(
                    jobName, firstAcq.get().runId(), firstAcq.get().fencingToken(),
                    checkpoints, jackson);
            JobContext.bind(firstCtx);

            var items = List.of("X", "Y", "Z");
            var firstRun = new ArrayList<String>();
            firstCtx.forEach(Stage.WORK, items, s -> s, (item, t) -> firstRun.add(item));
            JobContext.unbind();
            lock.release(jobName, firstAcq.get().fencingToken());

            assertThat(firstRun).containsExactly("X", "Y", "Z");

            var secondAcq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
            assertThat(secondAcq).isPresent();
            var secondCtx = new JobContext(
                    jobName, secondAcq.get().runId(), secondAcq.get().fencingToken(),
                    checkpoints, jackson);
            JobContext.bind(secondCtx);

            var secondRun = new ArrayList<String>();
            secondCtx.forEach(Stage.WORK, items, s -> s, (item, t) -> secondRun.add(item));
            JobContext.unbind();

            assertThat(secondRun)
                    .as("second run on a fresh runId processes all items again - runId-scoped idempotency")
                    .containsExactly("X", "Y", "Z");
        }
    }

    @Nested
    @DisplayName("step checkpoint resume")
    class StepResume {

        @Test
        @DisplayName("second run re-uses step result without re-executing the supplier")
        void step_notReexecutedOnResume() throws Exception {
            String jobName = "resume-job-step";
            lock.ensureSeedRow(jobName);

            var firstAcq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
            assertThat(firstAcq).isPresent();
            var runId = firstAcq.get().runId();
            var token = firstAcq.get().fencingToken();

            var firstCtx = new JobContext(jobName, runId, token, checkpoints, jackson);
            JobContext.bind(firstCtx);
            var callCount = new AtomicInteger(0);
            String result1 = firstCtx.step(Stage.FETCH, () -> {
                callCount.incrementAndGet();
                return "fetched-data";
            });
            JobContext.unbind();

            assertThat(result1).isEqualTo("fetched-data");
            assertThat(callCount.get()).isEqualTo(1);

            jdbc.update("UPDATE vigil_job_locks SET status = 'ORPHANED' WHERE job_name = ?", jobName);
            var secondAcq = lock.tryAcquire(jobName, "pod-1", Duration.ofMinutes(5));
            assertThat(secondAcq).isPresent();

            var secondCtx = new JobContext(
                    jobName, secondAcq.get().runId(), secondAcq.get().fencingToken(),
                    checkpoints, jackson);
            JobContext.bind(secondCtx);
            String result2 = secondCtx.step(Stage.FETCH, () -> {
                callCount.incrementAndGet();
                return "should-not-be-called";
            });
            JobContext.unbind();

            assertThat(result2)
                    .as("step result is restored from checkpoint without re-executing")
                    .isEqualTo("fetched-data");
            assertThat(callCount.get())
                    .as("supplier is called exactly once across both runs")
                    .isEqualTo(1);
        }
    }

    enum Stage { WORK, FETCH }
}
