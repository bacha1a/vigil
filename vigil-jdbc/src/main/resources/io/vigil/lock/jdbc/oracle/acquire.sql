UPDATE vigil_job_locks
SET holder      = ?,
    token       = token + 1,
    run_id      = CASE WHEN status = 'FREE' AND expires_at >= SYS_EXTRACT_UTC(SYSTIMESTAMP) THEN ? ELSE run_id END,
    acquired_at = SYS_EXTRACT_UTC(SYSTIMESTAMP),
    expires_at  = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'SECOND'),
    status      = 'HELD'
WHERE job_name = ?
  AND status <> 'PAUSED'
  AND (status = 'FREE' OR status = 'ORPHANED' OR expires_at < SYS_EXTRACT_UTC(SYSTIMESTAMP))
