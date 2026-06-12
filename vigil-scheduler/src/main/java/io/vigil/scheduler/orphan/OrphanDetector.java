package io.vigil.scheduler.orphan;

import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

public class OrphanDetector {

    private static final Logger log = LoggerFactory.getLogger(OrphanDetector.class);

    private static final String QUERY = """
            SELECT job_name FROM vigil_job_locks
            WHERE status = 'ORPHANED' OR (status = 'HELD' AND expires_at < ?)
            """;

    private final JdbcTemplate           jdbc;
    private final FencedJobWorkerRegistry registry;
    private final long                   scanIntervalMs;

    public OrphanDetector(JdbcTemplate jdbc, FencedJobWorkerRegistry registry, long scanIntervalMs) {
        this.jdbc           = jdbc;
        this.registry       = registry;
        this.scanIntervalMs = scanIntervalMs;
    }

    @PostConstruct
    public void start() {
        Thread.ofPlatform().daemon(true).name("vigil-orphan-detector").start(this::loop);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                scan();
            } catch (RuntimeException e) {
                log.warn("[Vigil] Orphan scan threw - will retry next interval", e);
            }
        }
    }

    public void scan() {
        var orphans = jdbc.queryForList(QUERY, String.class, Timestamp.from(Instant.now()));
        for (var jobName : orphans) {
            if (registry.isRegistered(jobName)) {
                log.info("[Vigil] Orphan detected for job '{}', submitting recovery", jobName);
                registry.submitRecovery(jobName);
            }
        }
    }
}
