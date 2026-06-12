package io.vigil.scheduler.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerInterruptRegressionTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("a stolen-lock interrupt during a tick must not leave the scheduler thread interrupted")
    void tickThatInterruptsItself_doesNotLeaveThreadInterrupted() {
        FencedScheduledBeanPostProcessor.runTickSafely("job", () -> Thread.currentThread().interrupt());

        assertThat(Thread.currentThread().isInterrupted())
                .as("cron loop's next Thread.sleep would abort and stop scheduling if the flag stayed set")
                .isFalse();
    }

    @Test
    @DisplayName("a tick that throws is swallowed and does not interrupt the scheduler thread")
    void tickThatThrows_isSwallowed() {
        FencedScheduledBeanPostProcessor.runTickSafely("job", () -> { throw new RuntimeException("boom"); });

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
