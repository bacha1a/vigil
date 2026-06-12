SELECT l.job_name, l.status, l.holder, l.token, l.run_id,
       c.stage_name, c.updated_at,
       r.started_at, r.finished_at, r.items_processed, r.error_message
FROM vigil_job_locks l
LEFT JOIN vigil_job_checkpoints c
  ON l.job_name = c.job_name
 AND l.run_id   = c.run_id
 AND c.updated_at = (
     SELECT MAX(c2.updated_at)
     FROM vigil_job_checkpoints c2
     WHERE c2.job_name = l.job_name AND c2.run_id = l.run_id
 )
LEFT JOIN vigil_job_runs r
  ON l.job_name = r.job_name
 AND r.finished_at = (
     SELECT MAX(r2.finished_at)
     FROM vigil_job_runs r2
     WHERE r2.job_name = l.job_name AND r2.status <> 'RUNNING'
 )
