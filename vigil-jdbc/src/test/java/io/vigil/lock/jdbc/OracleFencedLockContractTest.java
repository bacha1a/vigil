package io.vigil.lock.jdbc;

import io.vigil.core.spi.FencedLock;
import io.vigil.testkit.FencedLockContract;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OracleFencedLockContractTest extends FencedLockContract {

    @Container
    static final OracleContainer DB = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-free:slim-faststart"));

    static FencedLock lock;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
                CREATE TABLE vigil_job_locks (
                    job_name    VARCHAR2(255) NOT NULL PRIMARY KEY,
                    run_id      VARCHAR2(36)  NOT NULL,
                    holder      VARCHAR2(255) NOT NULL,
                    token       NUMBER(19)    DEFAULT 0 NOT NULL,
                    acquired_at TIMESTAMP     NOT NULL,
                    expires_at  TIMESTAMP     NOT NULL,
                    status      VARCHAR2(16)  NOT NULL
                )""");
        lock = new JdbcFencedLock(jdbc, tx);
    }

    @Override
    protected FencedLock lock() {
        return lock;
    }
}
