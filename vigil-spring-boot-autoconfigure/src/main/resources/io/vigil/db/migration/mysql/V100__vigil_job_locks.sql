CREATE TABLE vigil_job_locks (
    job_name    VARCHAR(255) NOT NULL,
    run_id      VARCHAR(36)  NOT NULL,
    holder      VARCHAR(255) NOT NULL,
    token       BIGINT       NOT NULL DEFAULT 0,
    acquired_at DATETIME(3)  NOT NULL,
    expires_at  DATETIME(3)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    PRIMARY KEY (job_name),
    KEY idx_vigil_locks_expiry (expires_at, status),
    CONSTRAINT vigil_job_locks_status_check CHECK (status IN ('FREE','HELD','ORPHANED','PAUSED'))
);
