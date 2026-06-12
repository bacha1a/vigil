package io.vigil.scheduler.history;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class VigilRunHistoryRetentionTask {

    private static final Logger log = LoggerFactory.getLogger(VigilRunHistoryRetentionTask.class);

    private static final String SQL_PURGE = loadSql();

    private final JdbcTemplate jdbc;
    private final int          retention;
    private final long         intervalMs;

    public VigilRunHistoryRetentionTask(JdbcTemplate jdbc, int retention, long intervalMs) {
        this.jdbc       = jdbc;
        this.retention  = retention;
        this.intervalMs = intervalMs;
    }

    @PostConstruct
    public void start() {
        Thread.ofPlatform().daemon(true).name("vigil-run-history-retention").start(this::loop);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            purge();
        }
    }

    public void purge() {
        int deleted = jdbc.update(SQL_PURGE, retention);
        if (deleted > 0) {
            log.info("[Vigil] Purged {} job run history rows (keeping last {} per job)", deleted, retention);
        }
    }

    private static String loadSql() {
        var path = "/io/vigil/scheduler/purge-run-history.sql";
        try (InputStream is = VigilRunHistoryRetentionTask.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("SQL file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SQL: purge-run-history.sql", e);
        }
    }
}
