CREATE TABLE IF NOT EXISTS vigil_job_runs (
    job_name         VARCHAR(255) NOT NULL,
    run_id           VARCHAR(36)  NOT NULL,
    started_at       TIMESTAMP    NOT NULL,
    finished_at      TIMESTAMP,
    status           VARCHAR(16)  CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'STOLEN')),
    items_processed  BIGINT       NOT NULL DEFAULT 0,
    error_message    TEXT,
    PRIMARY KEY (job_name, run_id)
);
