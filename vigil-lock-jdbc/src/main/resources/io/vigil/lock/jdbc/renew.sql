UPDATE vigil_job_locks SET expires_at = ? WHERE job_name = ? AND token = ? AND status = 'HELD'
