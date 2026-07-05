SELECT job_name, run_id, stage_name, status, stored_value, value_type, fencing_token
FROM vigil_job_checkpoints
WHERE job_name = ? AND run_id = ? AND stage_name = ?
