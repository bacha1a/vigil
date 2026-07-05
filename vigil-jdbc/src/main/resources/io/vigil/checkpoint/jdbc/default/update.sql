UPDATE vigil_job_checkpoints
SET status = ?, stored_value = ?, value_type = ?, fencing_token = ?, updated_at = ?
WHERE job_name = ? AND run_id = ? AND stage_name = ?
