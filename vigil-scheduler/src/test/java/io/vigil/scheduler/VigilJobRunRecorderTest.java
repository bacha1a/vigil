package io.vigil.scheduler;

import io.vigil.scheduler.history.VigilJobRunRecorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VigilJobRunRecorderTest {

    JdbcTemplate         jdbc;
    VigilJobRunRecorder  recorder;

    @BeforeEach
    void setUp() {
        jdbc     = mock(JdbcTemplate.class);
        recorder = new VigilJobRunRecorder(jdbc);
    }

    @Nested
    @DisplayName("startRun")
    class StartRun {

        @Test
        @DisplayName("inserts a RUNNING row for the given job and run")
        void insertsRunningRow() {
            recorder.startRun("billing-job", "run-123", Instant.parse("2026-03-01T10:00:00Z"));

            verify(jdbc).update(anyString(),
                    eq("billing-job"), eq("run-123"), any());
        }

        @Test
        @DisplayName("SQL contains vigil_job_runs table reference")
        void sqlReferencesTable() {
            recorder.startRun("billing-job", "run-123", Instant.now());

            verify(jdbc).update(contains("vigil_job_runs"),
                    anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("finishRun")
    class FinishRun {

        @Test
        @DisplayName("updates the row with finished_at, status, items_processed, and error_message")
        void updatesRow() {
            recorder.finishRun("billing-job", "run-123",
                    Instant.parse("2026-03-01T10:05:00Z"), "SUCCESS", 100L, null);

            verify(jdbc).update(anyString(),
                    any(), eq("SUCCESS"), eq(100L), eq(null),
                    eq("billing-job"), eq("run-123"));
        }

        @Test
        @DisplayName("passes error message when status is FAILED")
        void passesErrorMessage() {
            recorder.finishRun("billing-job", "run-123",
                    Instant.now(), "FAILED", 42L, "Connection refused");

            verify(jdbc).update(anyString(),
                    any(), eq("FAILED"), eq(42L), eq("Connection refused"),
                    eq("billing-job"), eq("run-123"));
        }
    }
}
