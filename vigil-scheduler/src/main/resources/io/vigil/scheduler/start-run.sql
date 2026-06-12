INSERT INTO vigil_job_runs (job_name, run_id, started_at, status)
VALUES (?, ?, ?, 'RUNNING')
ON CONFLICT (job_name, run_id) DO NOTHING
