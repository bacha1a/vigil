package io.vigil.scheduler;

import io.vigil.scheduler.worker.FencedJobWorkerRegistry;

import io.vigil.scheduler.advisor.VigilTtlAdvisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VigilTtlAdvisorTest {

    FencedJobWorkerRegistry registry;
    VigilTtlAdvisor         advisor;

    @BeforeEach
    void setUp() {
        registry = new FencedJobWorkerRegistry();
        advisor  = new VigilTtlAdvisor(registry);
    }

    @Nested
    @DisplayName("onRunCompleted")
    class OnRunCompleted {

        @Test
        @DisplayName("records the duration in the registry")
        void recordsDurationInRegistry() {
            advisor.onRunCompleted("billing-job", 1000L, 60);

            assertThat(registry.peakDurationMs("billing-job")).hasValue(1000L);
        }

        @Test
        @DisplayName("does not warn when peak is below half the TTL")
        void noWarnWhenBelowHalfTtl() {
            advisor.onRunCompleted("billing-job", 29_000L, 60);

            assertThat(advisor.warnedJobs).doesNotContain("billing-job");
        }

        @Test
        @DisplayName("warns when peak exceeds half the TTL")
        void warnsWhenPeakExceedsHalfTtl() {
            advisor.onRunCompleted("billing-job", 31_000L, 60);

            assertThat(advisor.warnedJobs).contains("billing-job");
        }

        @Test
        @DisplayName("warns only once per job regardless of how many runs exceed the threshold")
        void warnsOnlyOnce() {
            advisor.onRunCompleted("billing-job", 31_000L, 60);
            advisor.onRunCompleted("billing-job", 40_000L, 60);
            advisor.onRunCompleted("billing-job", 50_000L, 60);

            assertThat(advisor.warnedJobs).containsExactly("billing-job");
        }

        @Test
        @DisplayName("tracks warnings independently per job name")
        void independentPerJob() {
            advisor.onRunCompleted("billing-job", 31_000L, 60);
            advisor.onRunCompleted("report-job",  2_000L,  60);

            assertThat(advisor.warnedJobs).containsExactly("billing-job");
        }
    }

    @Nested
    @DisplayName("peakDurationMs via registry")
    class PeakDuration {

        @Test
        @DisplayName("returns empty when no runs have been recorded")
        void emptyWhenNoRuns() {
            assertThat(registry.peakDurationMs("unknown-job")).isEmpty();
        }

        @Test
        @DisplayName("returns the maximum of recent durations")
        void returnsMax() {
            advisor.onRunCompleted("billing-job", 1000L, 300);
            advisor.onRunCompleted("billing-job", 5000L, 300);
            advisor.onRunCompleted("billing-job", 2000L, 300);

            assertThat(registry.peakDurationMs("billing-job")).hasValue(5000L);
        }

        @Test
        @DisplayName("only retains the last 10 durations")
        void retainsLastTen() {
            for (int i = 1; i <= 12; i++) {
                advisor.onRunCompleted("billing-job", (long) i * 1000, 300);
            }

            assertThat(registry.peakDurationMs("billing-job")).hasValue(12_000L);
        }
    }
}
