package io.vigil.checkpoint.jdbc;

import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.lock.jdbc.JdbcFencedLock;
import io.vigil.testkit.CheckpointManagerContract;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresCheckpointContractTest extends CheckpointManagerContract {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16");

    static FencedLock            lock;
    static CheckpointManager     checkpoints;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vigil_job_locks (
                    job_name    VARCHAR(255) NOT NULL PRIMARY KEY,
                    run_id      VARCHAR(36)  NOT NULL,
                    holder      VARCHAR(255) NOT NULL,
                    token       BIGINT       NOT NULL DEFAULT 0,
                    acquired_at TIMESTAMP    NOT NULL,
                    expires_at  TIMESTAMP    NOT NULL,
                    status      VARCHAR(16)  NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vigil_job_checkpoints (
                    job_name      VARCHAR(255) NOT NULL,
                    run_id        VARCHAR(36)  NOT NULL,
                    stage_name    VARCHAR(255) NOT NULL,
                    status        VARCHAR(16)  NOT NULL,
                    stored_value  TEXT,
                    value_type    VARCHAR(512),
                    fencing_token BIGINT       NOT NULL,
                    updated_at    TIMESTAMP    NOT NULL,
                    PRIMARY KEY (job_name, run_id, stage_name)
                )""");
        lock = new JdbcFencedLock(jdbc, tx);
        checkpoints = new JdbcCheckpointManager(jdbc, tx);
    }

    @Override
    protected CheckpointManager checkpoints() {
        return checkpoints;
    }

    @Override
    protected FencedLock lock() {
        return lock;
    }
}
