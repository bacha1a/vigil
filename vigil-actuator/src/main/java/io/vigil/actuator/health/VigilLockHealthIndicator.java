package io.vigil.actuator.health;

import io.vigil.core.spi.FencedLock;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class VigilLockHealthIndicator implements HealthIndicator {

    private final FencedLock lock;

    public VigilLockHealthIndicator(FencedLock lock) {
        this.lock = lock;
    }

    @Override
    public Health health() {
        String backend = lock.getClass().getSimpleName();
        try {
            lock.checkConnectivity();
            return Health.up().withDetail("backend", backend).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("backend", backend).build();
        }
    }
}
