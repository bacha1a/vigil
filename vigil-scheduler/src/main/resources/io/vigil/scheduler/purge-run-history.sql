DELETE FROM vigil_job_runs
WHERE run_id NOT IN (
    SELECT run_id FROM (
        SELECT run_id,
               ROW_NUMBER() OVER (PARTITION BY job_name ORDER BY started_at DESC) AS rn
        FROM vigil_job_runs
    ) ranked
    WHERE rn <= ?
)
