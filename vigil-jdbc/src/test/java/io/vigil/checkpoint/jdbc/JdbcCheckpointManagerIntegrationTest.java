package io.vigil.checkpoint.jdbc;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcCheckpointManagerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcCheckpointManager manager;
    static JdbcTemplate           jdbc;

    static final String JOB    = "test-job";
    static final long   TOKEN  = 42L;
    static final UUID   RUN_ID = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        manager = new JdbcCheckpointManager(jdbc, tx);

        jdbc.execute(
                "CREATE TABLE vigil_job_locks (" +
                "job_name    VARCHAR(255) NOT NULL, " +
                "run_id      VARCHAR(36)  NOT NULL, " +
                "holder      VARCHAR(255) NOT NULL, " +
                "token       BIGINT       NOT NULL DEFAULT 0, " +
                "acquired_at TIMESTAMP    NOT NULL, " +
                "expires_at  TIMESTAMP    NOT NULL, " +
                "status      VARCHAR(16)  NOT NULL, " +
                "CONSTRAINT pk_vigil_job_locks PRIMARY KEY (job_name))");

        jdbc.execute(
                "CREATE TABLE vigil_job_checkpoints (" +
                "job_name      VARCHAR(255) NOT NULL, " +
                "run_id        VARCHAR(36)  NOT NULL, " +
                "stage_name    VARCHAR(255) NOT NULL, " +
                "status        VARCHAR(16)  NOT NULL, " +
                "stored_value  TEXT, " +
                "value_type    VARCHAR(512), " +
                "fencing_token BIGINT       NOT NULL, " +
                "updated_at    TIMESTAMP    NOT NULL, " +
                "PRIMARY KEY (job_name, run_id, stage_name))");

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO vigil_job_locks (job_name, run_id, holder, token, acquired_at, expires_at, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                JOB, UUID.randomUUID().toString(), "pod-1", TOKEN, now,
                Timestamp.from(Instant.now().plusSeconds(300)), "HELD");
    }

    @BeforeEach
    void resetCheckpoints() {
        jdbc.update("DELETE FROM vigil_job_checkpoints WHERE job_name = ?", JOB);
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("new entry is persisted and can be loaded back with all fields intact")
        void newEntry_canBeLoaded() {
            CheckpointEntry entry = entry("stage-1", CheckpointStatus.IN_PROGRESS, "val", "String");
            manager.save(entry);

            Optional<CheckpointEntry> loaded = manager.load(JOB, RUN_ID, "stage-1");
            assertThat(loaded).isPresent();
            assertThat(loaded.get().status()).isEqualTo(CheckpointStatus.IN_PROGRESS);
            assertThat(loaded.get().storedValue()).isEqualTo("val");
            assertThat(loaded.get().valueType()).isEqualTo("String");
            assertThat(loaded.get().fencingToken()).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("correct fencing token succeeds without throwing")
        void correctToken_succeeds() {
            CheckpointEntry entry = entry("stage-2", CheckpointStatus.COMPLETE, null, null);
            manager.save(entry);
        }

        @Test
        @DisplayName("stale fencing token throws LockStolenException - fencing guard rejects zombie writes")
        void staleToken_throwsLockStolenException() {
            CheckpointEntry stale = new CheckpointEntry(JOB, RUN_ID, "stage-3",
                    CheckpointStatus.IN_PROGRESS, null, null, 999L);

            assertThatThrownBy(() -> manager.save(stale))
                    .isInstanceOf(LockStolenException.class);
        }

        @Test
        @DisplayName("saving IN_PROGRESS then COMPLETE for the same stage updates the record")
        void sameEntry_idempotentUpsert() {
            CheckpointEntry inProgress = entry("stage-4", CheckpointStatus.IN_PROGRESS, null, null);
            manager.save(inProgress);

            CheckpointEntry complete = entry("stage-4", CheckpointStatus.COMPLETE, "result", "String");
            manager.save(complete);

            Optional<CheckpointEntry> loaded = manager.load(JOB, RUN_ID, "stage-4");
            assertThat(loaded).isPresent();
            assertThat(loaded.get().status()).isEqualTo(CheckpointStatus.COMPLETE);
        }

        @Test
        @DisplayName("entry with a higher stored token is not overwritten by a lower-token save")
        void existingHigherToken_isNotOverwritten() {
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update(
                    "INSERT INTO vigil_job_checkpoints " +
                    "(job_name, run_id, stage_name, status, stored_value, value_type, fencing_token, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    JOB, RUN_ID.toString(), "stage-5",
                    CheckpointStatus.COMPLETE.name(), "kept", "String", 99L, now);

            CheckpointEntry older = new CheckpointEntry(JOB, RUN_ID, "stage-5",
                    CheckpointStatus.IN_PROGRESS, "overwrite-attempt", "String", TOKEN);
            manager.save(older);

            Optional<CheckpointEntry> loaded = manager.load(JOB, RUN_ID, "stage-5");
            assertThat(loaded).isPresent();
            assertThat(loaded.get().fencingToken()).isEqualTo(99L);
            assertThat(loaded.get().storedValue()).isEqualTo("kept");
        }

        @Test
        @DisplayName("stored value and value type are round-tripped exactly")
        void storedValueAndType_arePreserved() {
            manager.save(entry("stage-all", CheckpointStatus.IN_PROGRESS, "{\"page\":3}", "java.lang.Integer"));

            Optional<CheckpointEntry> loaded = manager.load(JOB, RUN_ID, "stage-all");
            assertThat(loaded).isPresent();
            assertThat(loaded.get().storedValue()).isEqualTo("{\"page\":3}");
            assertThat(loaded.get().valueType()).isEqualTo("java.lang.Integer");
            assertThat(loaded.get().status()).isEqualTo(CheckpointStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("run ID UUID is preserved exactly through the VARCHAR(36) round-trip")
        void runId_isPreservedAsUUID() {
            manager.save(entry("stage-9", CheckpointStatus.COMPLETE, null, null));

            Optional<CheckpointEntry> loaded = manager.load(JOB, RUN_ID, "stage-9");
            assertThat(loaded).isPresent();
            assertThat(loaded.get().runId()).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("different stages for the same job are independent")
        void differentStages_areIndependent() {
            manager.save(entry("stage-one", CheckpointStatus.COMPLETE, null, null));
            manager.save(entry("stage-two", CheckpointStatus.IN_PROGRESS, null, null));

            assertThat(manager.isComplete(JOB, RUN_ID, "stage-one")).isTrue();
            assertThat(manager.isComplete(JOB, RUN_ID, "stage-two")).isFalse();
            assertThat(manager.load(JOB, RUN_ID, "stage-one")).isPresent();
            assertThat(manager.load(JOB, RUN_ID, "stage-two")).isPresent();
        }
    }

    @Nested
    @DisplayName("isComplete")
    class IsComplete {

        @Test
        @DisplayName("returns true after a COMPLETE checkpoint is saved")
        void afterCompleteCheckpoint_returnsTrue() {
            manager.save(entry("stage-6", CheckpointStatus.COMPLETE, null, null));

            assertThat(manager.isComplete(JOB, RUN_ID, "stage-6")).isTrue();
        }

        @Test
        @DisplayName("returns false when no checkpoint exists for that stage")
        void noEntry_returnsFalse() {
            assertThat(manager.isComplete(JOB, RUN_ID, "nonexistent-stage")).isFalse();
        }

        @Test
        @DisplayName("returns false when the checkpoint is IN_PROGRESS")
        void inProgressCheckpoint_returnsFalse() {
            manager.save(entry("stage-ip", CheckpointStatus.IN_PROGRESS, null, null));

            assertThat(manager.isComplete(JOB, RUN_ID, "stage-ip")).isFalse();
        }
    }

    @Nested
    @DisplayName("load")
    class Load {

        @Test
        @DisplayName("returns empty when no checkpoint exists for the given stage")
        void noEntry_returnsEmpty() {
            assertThat(manager.load(JOB, RUN_ID, "nonexistent-stage")).isEmpty();
        }
    }

    private CheckpointEntry entry(String stageName, CheckpointStatus status, String value, String valueType) {
        return new CheckpointEntry(JOB, RUN_ID, stageName, status, value, valueType, TOKEN);
    }
}
