UPDATE vigil_job_locks SET expires_at = CURRENT_TIMESTAMP + make_interval(secs => ?) WHERE job_name = ? AND token = ? AND status = 'HELD' AND expires_at >= CURRENT_TIMESTAMP
