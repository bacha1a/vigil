package io.vigil.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedGCPauseTest {

    @Test
    void pauseSleepsForAtLeastTheRequestedDuration() throws InterruptedException {
        Duration sleep = Duration.ofMillis(50);

        long start = System.nanoTime();
        SimulatedGCPause.pause(sleep);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(45L);
    }

    @Test
    void pauseWithZeroDurationReturnsImmediately() throws InterruptedException {
        SimulatedGCPause.pause(Duration.ZERO);
    }
}
