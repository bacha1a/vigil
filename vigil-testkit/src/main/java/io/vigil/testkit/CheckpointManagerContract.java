package io.vigil.testkit;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CheckpointManager contract")
public abstract class CheckpointManagerContract {

    protected abstract CheckpointManager checkpoints();

    protected abstract FencedLock lock();

    private String job;
    private UUID   runId;
    private long   token;

    @BeforeEach
    void seedAndAcquire() {
        job = "job-" + UUID.randomUUID();
        lock().ensureSeedRow(job);
        LockAcquisition a = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(60)).orElseThrow();
        runId = a.runId();
        token = a.fencingToken();
    }

    private CheckpointEntry entry(String stage, CheckpointStatus status, String value, long fencingToken) {
        return new CheckpointEntry(job, runId, stage, status, value, String.class.getName(), fencingToken);
    }

    @Test
    @DisplayName("save then load round-trips value and status")
    void saveThenLoad_roundTrips() {
        checkpoints().save(entry("STAGE", CheckpointStatus.COMPLETE, "\"payload\"", token));
        CheckpointEntry loaded = checkpoints().load(job, runId, "STAGE").orElseThrow();
        assertThat(loaded.storedValue()).isEqualTo("\"payload\"");
        assertThat(loaded.status()).isEqualTo(CheckpointStatus.COMPLETE);
    }

    @Test
    @DisplayName("isComplete reflects a COMPLETE checkpoint and is false for unknown stages")
    void isComplete_reflectsStatus() {
        assertThat(checkpoints().isComplete(job, runId, "STAGE")).isFalse();
        checkpoints().save(entry("STAGE", CheckpointStatus.COMPLETE, "\"x\"", token));
        assertThat(checkpoints().isComplete(job, runId, "STAGE")).isTrue();
    }

    @Test
    @DisplayName("hasAnyCheckpoint is false before any save, true after")
    void hasAnyCheckpoint_tracksPresence() {
        assertThat(checkpoints().hasAnyCheckpoint(job, runId)).isFalse();
        checkpoints().save(entry("STAGE", CheckpointStatus.IN_PROGRESS, "\"x\"", token));
        assertThat(checkpoints().hasAnyCheckpoint(job, runId)).isTrue();
    }

    @Test
    @DisplayName("a save carrying a stale fencing token is rejected (fenced out)")
    void staleToken_isRejected() {
        assertThatThrownBy(() -> checkpoints().save(entry("STAGE", CheckpointStatus.COMPLETE, "\"x\"", token - 1)))
                .isInstanceOf(LockStolenException.class);
    }

    @Test
    @DisplayName("clearRun with the held token removes all of the run's checkpoints")
    void clearRun_withHeldToken_removesCheckpoints() {
        checkpoints().save(entry("A", CheckpointStatus.COMPLETE, "\"a\"", token));
        checkpoints().save(entry("B", CheckpointStatus.COMPLETE, "\"b\"", token));
        assertThat(checkpoints().hasAnyCheckpoint(job, runId)).isTrue();

        checkpoints().clearRun(job, runId, token);

        assertThat(checkpoints().hasAnyCheckpoint(job, runId)).isFalse();
        assertThat(checkpoints().load(job, runId, "A")).isEmpty();
        assertThat(checkpoints().load(job, runId, "B")).isEmpty();
    }

    @Test
    @DisplayName("clearRun with a stale token is a no-op (protects the new holder's resume state)")
    void clearRun_withStaleToken_isNoOp() {
        checkpoints().save(entry("A", CheckpointStatus.COMPLETE, "\"a\"", token));

        checkpoints().clearRun(job, runId, token - 1);

        assertThat(checkpoints().hasAnyCheckpoint(job, runId)).isTrue();
        assertThat(checkpoints().load(job, runId, "A")).isPresent();
    }

    @Test
    @DisplayName("after failover the higher-token holder's write wins (resume overwrites)")
    void failoverResume_higherTokenOverwrites() throws InterruptedException {
        String j = "job-" + UUID.randomUUID();
        lock().ensureSeedRow(j);
        LockAcquisition a1 = lock().tryAcquire(j, "pod-A", Duration.ofSeconds(1)).orElseThrow();
        checkpoints().save(new CheckpointEntry(j, a1.runId(), "S", CheckpointStatus.IN_PROGRESS,
                "\"v1\"", String.class.getName(), a1.fencingToken()));

        Thread.sleep(2000);
        LockAcquisition a2 = lock().tryAcquire(j, "pod-B", Duration.ofSeconds(60)).orElseThrow();
        assertThat(a2.runId()).isEqualTo(a1.runId());
        assertThat(a2.fencingToken()).isGreaterThan(a1.fencingToken());

        checkpoints().save(new CheckpointEntry(j, a2.runId(), "S", CheckpointStatus.COMPLETE,
                "\"v2\"", String.class.getName(), a2.fencingToken()));
        CheckpointEntry loaded = checkpoints().load(j, a2.runId(), "S").orElseThrow();
        assertThat(loaded.storedValue()).isEqualTo("\"v2\"");
        assertThat(loaded.status()).isEqualTo(CheckpointStatus.COMPLETE);
    }
}
