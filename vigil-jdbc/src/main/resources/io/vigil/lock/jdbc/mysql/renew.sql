UPDATE vigil_job_locks SET expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND) WHERE job_name = ? AND token = ? AND status = 'HELD' AND expires_at >= CURRENT_TIMESTAMP(3)
