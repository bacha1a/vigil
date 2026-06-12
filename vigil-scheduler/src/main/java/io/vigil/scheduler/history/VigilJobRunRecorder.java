package io.vigil.scheduler.history;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

public class VigilJobRunRecorder {

    private static final String SQL_START_RUN  = loadSql("start-run.sql");
    private static final String SQL_FINISH_RUN = loadSql("finish-run.sql");

    private final JdbcTemplate jdbc;

    public VigilJobRunRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void startRun(String jobName, String runId, Instant startedAt) {
        jdbc.update(SQL_START_RUN, jobName, runId, Timestamp.from(startedAt));
    }

    public void finishRun(String jobName, String runId, Instant finishedAt,
                          String status, long itemsProcessed, String errorMessage) {
        jdbc.update(SQL_FINISH_RUN,
                Timestamp.from(finishedAt), status, itemsProcessed, errorMessage,
                jobName, runId);
    }

    private static String loadSql(String name) {
        var path = "/io/vigil/scheduler/" + name;
        try (InputStream is = VigilJobRunRecorder.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("SQL file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SQL: " + name, e);
        }
    }
}
