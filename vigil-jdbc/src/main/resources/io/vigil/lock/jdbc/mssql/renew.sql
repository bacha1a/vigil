UPDATE vigil_job_locks SET expires_at = DATEADD(SECOND, ?, SYSUTCDATETIME()) WHERE job_name = ? AND token = ? AND status = 'HELD' AND expires_at >= SYSUTCDATETIME()
