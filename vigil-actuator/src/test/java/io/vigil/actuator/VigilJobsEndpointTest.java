package io.vigil.actuator;

import io.vigil.actuator.model.JobStatusView;

import io.vigil.actuator.endpoint.VigilJobsEndpoint;

import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VigilJobsEndpointTest {

    JdbcTemplate            jdbc;
    FencedJobWorkerRegistry registry;
    VigilJobsEndpoint       endpoint;

    @BeforeEach
    void setUp() {
        jdbc     = mock(JdbcTemplate.class);
        registry = mock(FencedJobWorkerRegistry.class);
        endpoint = new VigilJobsEndpoint(jdbc, registry, null);
    }

    @Nested
    @DisplayName("jobs")
    class Jobs {

        @Test
        @DisplayName("maps all lock fields into JobStatusView")
        void mapsLockFieldsIntoView() {
            var ts = Timestamp.from(Instant.parse("2026-03-01T12:00:00Z"));
            var row = new java.util.HashMap<String, Object>();
            row.put("job_name",        "billing-job");
            row.put("status",          "HELD");
            row.put("holder",          "pod-1");
            row.put("token",           42L);
            row.put("run_id",          "run-uuid");
            row.put("stage_name",      "Stage.CHARGE");
            row.put("updated_at",      ts);
            row.put("started_at",      null);
            row.put("finished_at",     null);
            row.put("items_processed", null);
            row.put("error_message",   null);
            when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

            var views = endpoint.jobs();

            assertThat(views).hasSize(1);
            var view = views.get(0);
            assertThat(view.name()).isEqualTo("billing-job");
            assertThat(view.status()).isEqualTo("HELD");
            assertThat(view.holder()).isEqualTo("pod-1");
            assertThat(view.fencingToken()).isEqualTo(42L);
            assertThat(view.runId()).isEqualTo("run-uuid");
            assertThat(view.lastCheckpoint()).isEqualTo("Stage.CHARGE");
            assertThat(view.checkpointedAt()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
            assertThat(view.lastRunDurationMs()).isNull();
            assertThat(view.lastRunItemsProcessed()).isNull();
            assertThat(view.lastError()).isNull();
        }

        @Test
        @DisplayName("sets checkpointedAt to null when no checkpoint exists")
        void setsCheckpointedAtNullWhenNoCheckpoint() {
            var row = new java.util.HashMap<String, Object>();
            row.put("job_name",        "idle-job");
            row.put("status",          "FREE");
            row.put("holder",          "");
            row.put("token",           0L);
            row.put("run_id",          "some-uuid");
            row.put("stage_name",      null);
            row.put("updated_at",      null);
            row.put("started_at",      null);
            row.put("finished_at",     null);
            row.put("items_processed", null);
            row.put("error_message",   null);
            when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

            var views = endpoint.jobs();

            assertThat(views.get(0).checkpointedAt()).isNull();
            assertThat(views.get(0).lastCheckpoint()).isNull();
        }

        @Test
        @DisplayName("populates lastRunDurationMs, lastRunItemsProcessed, and lastError from vigil_job_runs")
        void populatesLastRunFields() {
            var checkpointTs = Timestamp.from(Instant.parse("2026-03-01T12:00:00Z"));
            var startedAt    = Timestamp.from(Instant.parse("2026-03-01T10:00:00Z"));
            var finishedAt   = Timestamp.from(Instant.parse("2026-03-01T10:05:30Z"));
            var row = new java.util.HashMap<String, Object>();
            row.put("job_name",        "billing-job");
            row.put("status",          "FREE");
            row.put("holder",          "");
            row.put("token",           0L);
            row.put("run_id",          "run-uuid");
            row.put("stage_name",      null);
            row.put("updated_at",      checkpointTs);
            row.put("started_at",      startedAt);
            row.put("finished_at",     finishedAt);
            row.put("items_processed", 500L);
            row.put("error_message",   "Connection refused");
            when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

            var view = endpoint.jobs().get(0);

            assertThat(view.lastRunDurationMs()).isEqualTo(330_000L);
            assertThat(view.lastRunItemsProcessed()).isEqualTo(500L);
            assertThat(view.lastError()).isEqualTo("Connection refused");
        }

        @Test
        @DisplayName("sets lastRunDurationMs and lastRunItemsProcessed to null when no completed run exists")
        void setsLastRunFieldsNullWhenNoRun() {
            var row = new java.util.HashMap<String, Object>();
            row.put("job_name",        "billing-job");
            row.put("status",          "FREE");
            row.put("holder",          "");
            row.put("token",           0L);
            row.put("run_id",          "run-uuid");
            row.put("stage_name",      null);
            row.put("updated_at",      null);
            row.put("started_at",      null);
            row.put("finished_at",     null);
            row.put("items_processed", null);
            row.put("error_message",   null);
            when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

            var view = endpoint.jobs().get(0);

            assertThat(view.lastRunDurationMs()).isNull();
            assertThat(view.lastRunItemsProcessed()).isNull();
            assertThat(view.lastError()).isNull();
        }

        @Test
        @DisplayName("returns empty list when no jobs are registered")
        void returnsEmptyWhenNoJobs() {
            when(jdbc.queryForList(anyString())).thenReturn(List.of());

            assertThat(endpoint.jobs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("act - trigger")
    class Trigger {

        @Test
        @DisplayName("submits recovery via the registry")
        void submitsRecovery() {
            endpoint.act("billing-job", "trigger");

            verify(registry).submitRecovery("billing-job");
        }
    }

    @Nested
    @DisplayName("act - pause")
    class Pause {

        @Test
        @DisplayName("sets lock status to PAUSED")
        void setsStatusPaused() {
            endpoint.act("billing-job", "pause");

            verify(jdbc).update(contains("PAUSED"), anyString());
        }
    }

    @Nested
    @DisplayName("act - resume")
    class Resume {

        @Test
        @DisplayName("sets status to FREE and submits immediate recovery")
        void setsFreeAndSubmitsRecovery() {
            endpoint.act("billing-job", "resume");

            verify(jdbc).update(contains("FREE"), anyString());
            verify(registry).submitRecovery("billing-job");
        }
    }

    @Nested
    @DisplayName("clear - lock")
    class ClearLock {

        @Test
        @DisplayName("updates the lock row when confirm is true")
        void updatesLockWhenConfirmed() {
            endpoint.clear("billing-job", "true", "lock");

            verify(jdbc).update(contains("FREE"), any(), anyString());
        }

        @Test
        @DisplayName("is a no-op when confirm is not true")
        void noOpWhenNotConfirmed() {
            endpoint.clear("billing-job", "false", "lock");

            verify(jdbc, never()).update(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("clear - checkpoint")
    class ClearCheckpoint {

        @Test
        @DisplayName("deletes all checkpoints for the job when confirm is true")
        void deletesCheckpointsWhenConfirmed() {
            endpoint.clear("billing-job", "true", "checkpoint");

            verify(jdbc).update(contains("vigil_job_checkpoints"), anyString());
        }

        @Test
        @DisplayName("is a no-op when confirm is not true")
        void noOpWhenNotConfirmed() {
            endpoint.clear("billing-job", "false", "checkpoint");

            verify(jdbc, never()).update(anyString(), anyString());
        }
    }
}
