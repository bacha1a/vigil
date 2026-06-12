package io.vigil.core.spi;

import io.vigil.core.model.CheckpointEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckpointManager {

    void save(CheckpointEntry entry);

    Optional<CheckpointEntry> load(String jobName, UUID runId, String stageName);

    boolean isComplete(String jobName, UUID runId, String stageName);

    default List<String> listStageNames(String jobName) { return List.of(); }

    default boolean hasAnyCheckpoint(String jobName, UUID runId) { return false; }
}
