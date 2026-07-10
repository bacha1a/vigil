UPDATE vigil_job_locks
SET holder      = ?,
    token       = token + 1,
    run_id      = CASE WHEN status = 'FREE' AND expires_at >= CURRENT_TIMESTAMP(3) THEN ? ELSE run_id END,
    acquired_at = CURRENT_TIMESTAMP(3),
    expires_at  = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
    status      = 'HELD'
WHERE job_name = ?
  AND status <> 'PAUSED'
  AND (status = 'FREE' OR status = 'ORPHANED' OR expires_at < CURRENT_TIMESTAMP(3))
