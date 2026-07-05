ALTER TABLE vigil_job_locks DROP CONSTRAINT vigil_job_locks_status_check;
ALTER TABLE vigil_job_locks ADD CONSTRAINT vigil_job_locks_status_check
    CHECK (status IN ('FREE', 'HELD', 'ORPHANED', 'PAUSED'));
