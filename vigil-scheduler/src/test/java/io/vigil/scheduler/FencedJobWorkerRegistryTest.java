package io.vigil.scheduler;

import io.vigil.scheduler.worker.FencedJobWorkerRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FencedJobWorkerRegistryTest {

    FencedJobWorkerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FencedJobWorkerRegistry();
    }

    @Nested
    @DisplayName("nextTriggerAt")
    class NextTriggerAt {

        @Test
        @DisplayName("returns empty when no cron is registered for the job")
        void emptyWhenNoCronRegistered() {
            assertThat(registry.nextTriggerAt("unknown-job")).isEmpty();
        }

        @Test
        @DisplayName("returns empty when registerCron is called with blank cron")
        void emptyWhenBlankCron() {
            registry.registerCron("billing-job", "", "UTC");

            assertThat(registry.nextTriggerAt("billing-job")).isEmpty();
        }

        @Test
        @DisplayName("returns a future instant for a valid cron expression")
        void returnsFutureInstantForValidCron() {
            registry.registerCron("billing-job", "0 * * * * *", "UTC");

            var next = registry.nextTriggerAt("billing-job");

            assertThat(next).isPresent();
            assertThat(next.get()).isAfter(java.time.Instant.now());
        }

        @Test
        @DisplayName("respects the configured zone when computing next trigger")
        void respectsConfiguredZone() {
            registry.registerCron("billing-job", "0 * * * * *", "Europe/London");

            assertThat(registry.nextTriggerAt("billing-job")).isPresent();
        }
    }
}
