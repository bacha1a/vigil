package io.vigil.core.model;

import java.util.UUID;

public record CheckpointEntry(
        String          jobName,
        UUID            runId,
        String          stageName,
        CheckpointStatus status,
        String          storedValue,
        String          valueType,
        long            fencingToken
) {}
