UPDATE vigil_job_locks
SET holder      = ?,
    token       = token + 1,
    run_id      = CASE WHEN status = 'FREE' AND expires_at >= CURRENT_TIMESTAMP THEN ? ELSE run_id END,
    acquired_at = CURRENT_TIMESTAMP,
    expires_at  = CURRENT_TIMESTAMP + make_interval(secs => ?),
    status      = 'HELD'
WHERE job_name = ?
  AND status <> 'PAUSED'
  AND (status = 'FREE' OR status = 'ORPHANED' OR expires_at < CURRENT_TIMESTAMP)
