UPDATE vigil_job_runs
SET finished_at     = ?,
    status          = ?,
    items_processed = ?,
    error_message   = ?
WHERE job_name = ? AND run_id = ?
