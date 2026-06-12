package io.vigil.actuator.model;

import java.time.Instant;

public record JobStatusView(
        String  name,
        String  status,
        String  holder,
        Long    fencingToken,
        String  runId,
        String  lastCheckpoint,
        Instant checkpointedAt,
        Instant nextTriggerAt,
        Long    lastRunDurationMs,
        Long    lastRunItemsProcessed,
        String  lastError,
        Long    checkpointSaveCount,
        Long    failoverRecoveryCount
) {}
