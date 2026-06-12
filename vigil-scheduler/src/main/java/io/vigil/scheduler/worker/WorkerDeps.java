package io.vigil.scheduler.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.scheduler.VigilJobLifecycleListener;
import io.vigil.scheduler.advisor.VigilTtlAdvisor;
import io.vigil.scheduler.heartbeat.HeartbeatDaemon;
import io.vigil.scheduler.history.VigilJobRunRecorder;

public record WorkerDeps(
        FencedLock fencedLock,
        CheckpointManager checkpointManager,
        HeartbeatDaemon heartbeatDaemon,
        String podId,
        ObjectMapper jackson,
        VigilTtlAdvisor advisor,
        VigilJobRunRecorder recorder,
        VigilJobLifecycleListener listener) {
}
