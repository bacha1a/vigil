package io.vigil.actuator.health;

import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VigilLockHealthIndicatorTest {

    @Test
    @DisplayName("reports UP with the backend name when connectivity succeeds")
    void up_whenReachable() {
        Health health = new VigilLockHealthIndicator(new StubLock(null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("backend", "StubLock");
    }

    @Test
    @DisplayName("reports DOWN when the connectivity probe throws")
    void down_whenProbeFails() {
        Health health = new VigilLockHealthIndicator(
                new StubLock(new IllegalStateException("backend unreachable"))).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("backend", "StubLock");
    }

    private static final class StubLock implements FencedLock {
        private final RuntimeException failure;

        StubLock(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void checkConnectivity() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public Optional<LockAcquisition> tryAcquire(String jobName, String podId, Duration ttl) {
            return Optional.empty();
        }

        @Override
        public boolean tryRenew(String jobName, long fencingToken, Duration ttl) {
            return false;
        }

        @Override
        public void release(String jobName, long fencingToken) {
        }
    }
}
