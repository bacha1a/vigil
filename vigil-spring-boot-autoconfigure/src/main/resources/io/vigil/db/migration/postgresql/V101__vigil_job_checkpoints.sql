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
);

CREATE INDEX IF NOT EXISTS idx_vigil_checkpoints_job ON vigil_job_checkpoints (job_name, run_id);
