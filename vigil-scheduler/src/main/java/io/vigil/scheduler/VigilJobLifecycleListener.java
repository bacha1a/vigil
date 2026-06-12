package io.vigil.scheduler;

public interface VigilJobLifecycleListener {
    default void onLockSkipped(String jobName) {}
    default void onLockAcquired(String jobName) {}
    default void onJobCompleted(String jobName, String status, long durationMs) {}
    default void onLockStolen(String jobName) {}
    default void onItemProcessed(String jobName) {}
    default void onCheckpointSaved(String jobName) {}
    default void onFailoverRecovery(String jobName) {}
}
