INSERT INTO vigil_job_locks (job_name, run_id, holder, token, acquired_at, expires_at, status)
SELECT ?, ?, 'init', 0, ?, ?, 'FREE' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM vigil_job_locks WHERE job_name = ?)
