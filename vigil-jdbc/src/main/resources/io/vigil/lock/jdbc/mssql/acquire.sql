UPDATE vigil_job_locks
SET holder      = ?,
    token       = token + 1,
    run_id      = CASE WHEN status = 'FREE' AND expires_at >= SYSUTCDATETIME() THEN ? ELSE run_id END,
    acquired_at = SYSUTCDATETIME(),
    expires_at  = DATEADD(SECOND, ?, SYSUTCDATETIME()),
    status      = 'HELD'
WHERE job_name = ?
  AND status <> 'PAUSED'
  AND (status = 'FREE' OR status = 'ORPHANED' OR expires_at < SYSUTCDATETIME())
