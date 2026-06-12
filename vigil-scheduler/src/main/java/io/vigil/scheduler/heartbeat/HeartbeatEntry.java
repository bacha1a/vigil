package io.vigil.scheduler.heartbeat;

import java.time.Duration;

public record HeartbeatEntry(
        String   jobName,
        long     fencingToken,
        Thread   jobThread,
        Duration ttl
) {}
