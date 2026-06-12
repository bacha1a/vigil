SELECT token, status, expires_at, run_id FROM vigil_job_locks WHERE job_name = ? FOR UPDATE
