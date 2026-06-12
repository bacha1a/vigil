package io.vigil.actuator;

import io.vigil.actuator.metrics.VigilMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VigilMetricsTest {

    SimpleMeterRegistry registry;
    VigilMetrics        metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new VigilMetrics(registry);
    }

    @Nested
    @DisplayName("onJobCompleted")
    class JobCompleted {

        @Test
        @DisplayName("increments vigil.job.executions.total with job and result tags")
        void incrementsCounter() {
            metrics.onJobCompleted("billing-job", "success", 0L);

            var counter = registry.get("vigil.job.executions.total")
                    .tags("job", "billing-job", "result", "success")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("tracks success and failure independently via tags")
        void tracksResultTagsIndependently() {
            metrics.onJobCompleted("billing-job", "success", 0L);
            metrics.onJobCompleted("billing-job", "failure", 0L);
            metrics.onJobCompleted("billing-job", "success", 0L);

            assertThat(registry.get("vigil.job.executions.total")
                    .tags("job", "billing-job", "result", "success").counter().count()).isEqualTo(2.0);
            assertThat(registry.get("vigil.job.executions.total")
                    .tags("job", "billing-job", "result", "failure").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("records duration on vigil.job.duration.seconds for success only")
        void recordsDurationOnSuccess() {
            metrics.onJobCompleted("billing-job", "success", 500L);

            var timer = registry.get("vigil.job.duration.seconds")
                    .tags("job", "billing-job")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500.0);
        }
    }

    @Nested
    @DisplayName("onLockAcquired / onLockSkipped")
    class LockAcquisition {

        @Test
        @DisplayName("onLockAcquired increments vigil.lock.acquisitions.total with result=acquired")
        void acquiredIncrementsCounter() {
            metrics.onLockAcquired("billing-job");

            assertThat(registry.get("vigil.lock.acquisitions.total")
                    .tags("job", "billing-job", "result", "acquired").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("onLockSkipped increments vigil.lock.acquisitions.total with result=skipped")
        void skippedIncrementsCounter() {
            metrics.onLockSkipped("billing-job");

            assertThat(registry.get("vigil.lock.acquisitions.total")
                    .tags("job", "billing-job", "result", "skipped").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("onLockStolen")
    class LockStolen {

        @Test
        @DisplayName("increments vigil.lock.stolen.total")
        void incrementsCounter() {
            metrics.onLockStolen("billing-job");

            assertThat(registry.get("vigil.lock.stolen.total")
                    .tags("job", "billing-job").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("onFailoverRecovery")
    class FailoverRecovery {

        @Test
        @DisplayName("increments vigil.failover.recoveries.total")
        void incrementsCounter() {
            metrics.onFailoverRecovery("billing-job");

            assertThat(registry.get("vigil.failover.recoveries.total")
                    .tags("job", "billing-job").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("onCheckpointSaved")
    class CheckpointSaved {

        @Test
        @DisplayName("increments vigil.checkpoint.saves.total")
        void incrementsCounter() {
            metrics.onCheckpointSaved("billing-job");

            assertThat(registry.get("vigil.checkpoint.saves.total")
                    .tags("job", "billing-job").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("onItemProcessed")
    class ItemProcessed {

        @Test
        @DisplayName("increments vigil.job.items.processed.total")
        void incrementsCounter() {
            metrics.onItemProcessed("billing-job");

            assertThat(registry.get("vigil.job.items.processed.total")
                    .tags("job", "billing-job").counter().count()).isEqualTo(1.0);
        }
    }
}
