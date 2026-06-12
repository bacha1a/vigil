UPDATE vigil_job_locks SET status = 'FREE' WHERE job_name = ? AND token = ? AND status = 'HELD'
