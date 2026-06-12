CREATE TABLE IF NOT EXISTS vigil_job_locks (
    job_name    VARCHAR(255) NOT NULL,
    run_id      VARCHAR(36)  NOT NULL,
    holder      VARCHAR(255) NOT NULL,
    token       BIGINT       NOT NULL DEFAULT 0,
    acquired_at TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    CONSTRAINT pk_vigil_job_locks PRIMARY KEY (job_name),
    CONSTRAINT vigil_job_locks_status_check CHECK (status IN ('FREE','HELD','ORPHANED'))
);

CREATE INDEX IF NOT EXISTS idx_vigil_locks_expiry ON vigil_job_locks (expires_at, status);
