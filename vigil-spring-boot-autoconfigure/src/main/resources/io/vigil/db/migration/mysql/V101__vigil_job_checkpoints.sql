CREATE TABLE vigil_job_checkpoints (
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
);
