package io.vigil.scheduler;

import io.vigil.scheduler.worker.FencedJobWorkerRegistry;

import io.vigil.scheduler.orphan.OrphanDetector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanDetectorTest {

    JdbcTemplate          jdbc;
    FencedJobWorkerRegistry registry;
    OrphanDetector        detector;

    @BeforeEach
    void setUp() {
        jdbc     = mock(JdbcTemplate.class);
        registry = mock(FencedJobWorkerRegistry.class);
        detector = new OrphanDetector(jdbc, registry, 60_000L);
    }

    @Nested
    @DisplayName("scan")
    class Scan {

        @Test
        @DisplayName("submits recovery for an orphaned job registered on this pod")
        void submitsRecoveryForRegisteredOrphan() {
            when(jdbc.queryForList(any(String.class), eq(String.class), any()))
                    .thenReturn(List.of("billing-job"));
            when(registry.isRegistered("billing-job")).thenReturn(true);

            detector.scan();

            verify(registry).submitRecovery("billing-job");
        }

        @Test
        @DisplayName("skips recovery for an orphaned job not registered on this pod")
        void skipsRecoveryForUnregisteredJob() {
            when(jdbc.queryForList(any(String.class), eq(String.class), any()))
                    .thenReturn(List.of("foreign-job"));
            when(registry.isRegistered("foreign-job")).thenReturn(false);

            detector.scan();

            verify(registry, never()).submitRecovery(any());
        }

        @Test
        @DisplayName("handles a mix of registered and unregistered orphans in one scan")
        void handlesRegisteredAndUnregisteredInOneScan() {
            when(jdbc.queryForList(any(String.class), eq(String.class), any()))
                    .thenReturn(List.of("job-a", "job-b", "job-c"));
            when(registry.isRegistered("job-a")).thenReturn(true);
            when(registry.isRegistered("job-b")).thenReturn(false);
            when(registry.isRegistered("job-c")).thenReturn(true);

            detector.scan();

            verify(registry).submitRecovery("job-a");
            verify(registry, never()).submitRecovery("job-b");
            verify(registry).submitRecovery("job-c");
        }

        @Test
        @DisplayName("does nothing when no orphaned jobs are found")
        void doesNothingWhenNoOrphansFound() {
            when(jdbc.queryForList(any(String.class), eq(String.class), any()))
                    .thenReturn(List.of());

            detector.scan();

            verify(registry, never()).isRegistered(any());
            verify(registry, never()).submitRecovery(any());
        }
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("creates a daemon thread named vigil-orphan-detector")
        void createsDaemonThreadWithCorrectName() throws InterruptedException {
            when(jdbc.queryForList(any(String.class), eq(String.class), any()))
                    .thenReturn(List.of());

            detector.start();
            Thread.sleep(50);

            var thread = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> "vigil-orphan-detector".equals(t.getName()))
                    .findFirst();

            assertThat(thread).isPresent();
            assertThat(thread.get().isDaemon()).isTrue();
        }
    }
}
