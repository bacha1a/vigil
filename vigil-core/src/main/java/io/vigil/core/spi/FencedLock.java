package io.vigil.core.spi;

import io.vigil.core.model.LockAcquisition;

import java.time.Duration;
import java.util.Optional;

public interface FencedLock {

    Optional<LockAcquisition> tryAcquire(String jobName, String podId, Duration ttl);

    boolean tryRenew(String jobName, long fencingToken, Duration ttl);

    default boolean tryRenew(String jobName, long fencingToken) {
        return tryRenew(jobName, fencingToken, Duration.ofSeconds(300));
    }

    void release(String jobName, long fencingToken);

    default void ensureSeedRow(String jobName) {}
}
