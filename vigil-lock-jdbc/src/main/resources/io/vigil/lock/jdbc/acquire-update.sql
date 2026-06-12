UPDATE vigil_job_locks
SET run_id = ?, holder = ?, token = ?, acquired_at = ?, expires_at = ?, status = 'HELD'
WHERE job_name = ?
