package io.vigil.autoconfigure.schema;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class VigilSchemaInitializer implements InitializingBean {

    private static final List<String> DDL_DEFAULT = List.of(
            """
            CREATE TABLE IF NOT EXISTS vigil_job_locks (
                job_name    VARCHAR(255) NOT NULL,
                run_id      VARCHAR(36)  NOT NULL,
                holder      VARCHAR(255) NOT NULL,
                token       BIGINT       NOT NULL DEFAULT 0,
                acquired_at TIMESTAMP    NOT NULL,
                expires_at  TIMESTAMP    NOT NULL,
                status      VARCHAR(16)  NOT NULL,
                CONSTRAINT pk_vigil_job_locks PRIMARY KEY (job_name),
                CONSTRAINT vigil_job_locks_status_check
                    CHECK (status IN ('FREE','HELD','ORPHANED','PAUSED'))
            )""",
            "CREATE INDEX IF NOT EXISTS idx_vigil_locks_expiry "
                    + "ON vigil_job_locks (expires_at, status)",
            """
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
            )""",
            "CREATE INDEX IF NOT EXISTS idx_vigil_checkpoints_job "
                    + "ON vigil_job_checkpoints (job_name, run_id)",
            """
            CREATE TABLE IF NOT EXISTS vigil_job_runs (
                job_name        VARCHAR(255) NOT NULL,
                run_id          VARCHAR(36)  NOT NULL,
                started_at      TIMESTAMP    NOT NULL,
                finished_at     TIMESTAMP,
                status          VARCHAR(16),
                items_processed BIGINT       NOT NULL DEFAULT 0,
                error_message   TEXT,
                PRIMARY KEY (job_name, run_id)
            )""");

    private static final List<String> DDL_MYSQL = List.of(
            """
            CREATE TABLE IF NOT EXISTS vigil_job_locks (
                job_name    VARCHAR(255) NOT NULL,
                run_id      VARCHAR(36)  NOT NULL,
                holder      VARCHAR(255) NOT NULL,
                token       BIGINT       NOT NULL DEFAULT 0,
                acquired_at DATETIME(3)  NOT NULL,
                expires_at  DATETIME(3)  NOT NULL,
                status      VARCHAR(16)  NOT NULL,
                PRIMARY KEY (job_name),
                KEY idx_vigil_locks_expiry (expires_at, status),
                CONSTRAINT vigil_job_locks_status_check
                    CHECK (status IN ('FREE','HELD','ORPHANED','PAUSED'))
            )""",
            """
            CREATE TABLE IF NOT EXISTS vigil_job_checkpoints (
                job_name      VARCHAR(255) NOT NULL,
                run_id        VARCHAR(36)  NOT NULL,
                stage_name    VARCHAR(255) NOT NULL,
                status        VARCHAR(16)  NOT NULL,
                stored_value  TEXT,
                value_type    VARCHAR(512),
                fencing_token BIGINT       NOT NULL,
                updated_at    DATETIME(3)  NOT NULL,
                PRIMARY KEY (job_name, run_id, stage_name),
                KEY idx_vigil_checkpoints_job (job_name, run_id)
            )""",
            """
            CREATE TABLE IF NOT EXISTS vigil_job_runs (
                job_name        VARCHAR(255) NOT NULL,
                run_id          VARCHAR(36)  NOT NULL,
                started_at      DATETIME(3)  NOT NULL,
                finished_at     DATETIME(3),
                status          VARCHAR(16),
                items_processed BIGINT       NOT NULL DEFAULT 0,
                error_message   TEXT,
                PRIMARY KEY (job_name, run_id)
            )""");

    private final JdbcTemplate jdbc;

    public VigilSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        for (String ddl : ddlFor(productName())) {
            jdbc.execute(ddl);
        }
    }

    private List<String> ddlFor(String product) {
        String p = product == null ? "" : product.toLowerCase();
        if (p.contains("mysql") || p.contains("maria")) {
            return DDL_MYSQL;
        }
        return DDL_DEFAULT;
    }

    private String productName() {
        return jdbc.execute((ConnectionCallback<String>) c ->
                c.getMetaData().getDatabaseProductName());
    }
}
