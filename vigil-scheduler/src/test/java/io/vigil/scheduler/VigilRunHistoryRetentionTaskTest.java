package io.vigil.scheduler;

import io.vigil.scheduler.history.VigilRunHistoryRetentionTask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VigilRunHistoryRetentionTaskTest {

    JdbcTemplate                jdbc;
    VigilRunHistoryRetentionTask task;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        task = new VigilRunHistoryRetentionTask(jdbc, 100, 3_600_000L);
    }

    @Nested
    @DisplayName("purge")
    class Purge {

        @Test
        @DisplayName("executes the purge SQL with the configured retention count")
        void executesPurgeSqlWithRetention() {
            when(jdbc.update(anyString(), eq(100))).thenReturn(0);

            task.purge();

            verify(jdbc).update(anyString(), eq(100));
        }

        @Test
        @DisplayName("uses custom retention count when configured")
        void usesCustomRetention() {
            var customTask = new VigilRunHistoryRetentionTask(jdbc, 50, 3_600_000L);
            when(jdbc.update(anyString(), eq(50))).thenReturn(0);

            customTask.purge();

            verify(jdbc).update(anyString(), eq(50));
        }
    }
}
