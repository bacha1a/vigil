CREATE TABLE vigil_job_runs (
    job_name        VARCHAR(255) NOT NULL,
    run_id          VARCHAR(36)  NOT NULL,
    started_at      DATETIME(3)  NOT NULL,
    finished_at     DATETIME(3),
    status          VARCHAR(16),
    items_processed BIGINT       NOT NULL DEFAULT 0,
    error_message   TEXT,
    PRIMARY KEY (job_name, run_id)
);
