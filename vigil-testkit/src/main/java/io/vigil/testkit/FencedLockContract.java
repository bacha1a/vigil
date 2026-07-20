package io.vigil.testkit;

import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("FencedLock contract")
public abstract class FencedLockContract {

    protected abstract FencedLock lock();

    private String job;

    @BeforeEach
    void seedFreshJob() {
        job = "job-" + UUID.randomUUID();
        lock().ensureSeedRow(job);
    }

    @Test
    @DisplayName("acquiring a free lock succeeds with a positive token and a runId")
    void acquireOnFreeLock_succeeds() {
        Optional<LockAcquisition> a = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(30));
        assertThat(a).isPresent();
        assertThat(a.get().fencingToken()).isPositive();
        assertThat(a.get().runId()).isNotNull();
    }

    @Test
    @DisplayName("a second acquire while the lock is held is rejected (mutual exclusion)")
    void secondAcquireWhileHeld_isRejected() {
        lock().tryAcquire(job, "pod-A", Duration.ofSeconds(30));
        assertThat(lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("fencing tokens strictly increase across acquisitions")
    void tokens_areMonotonic() {
        long t1 = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        lock().release(job, t1);
        long t2 = lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        assertThat(t2).isGreaterThan(t1);
    }

    @Test
    @DisplayName("renew succeeds with the held token and fails with a stale token")
    void renew_semantics() {
        long token = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        assertThat(lock().tryRenew(job, token, Duration.ofSeconds(30))).isTrue();
        assertThat(lock().tryRenew(job, token - 1, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("release with the wrong token is a no-op; release with the held token frees the lock")
    void release_isTokenGuarded() {
        long token = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        lock().release(job, token + 99);
        assertThat(lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30))).isEmpty();
        lock().release(job, token);
        assertThat(lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    @DisplayName("clean release then re-acquire yields a fresh runId (no accidental resume)")
    void cleanRelease_givesFreshRunId() {
        LockAcquisition first = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(30)).orElseThrow();
        lock().release(job, first.fencingToken());
        LockAcquisition second = lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30)).orElseThrow();
        assertThat(second.runId()).isNotEqualTo(first.runId());
    }

    @Test
    @DisplayName("expiry takeover reuses the runId (failover resume) and bumps the token")
    void expiryTakeover_reusesRunId() throws InterruptedException {
        LockAcquisition first = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(1)).orElseThrow();
        Thread.sleep(2000);
        LockAcquisition takeover = lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30)).orElseThrow();
        assertThat(takeover.runId()).isEqualTo(first.runId());
        assertThat(takeover.fencingToken()).isGreaterThan(first.fencingToken());
    }

    @Test
    @DisplayName("checkConnectivity succeeds against a reachable backend")
    void checkConnectivity_succeedsWhenReachable() {
        assertThatCode(() -> lock().checkConnectivity()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a stolen holder can no longer renew after another pod takes over")
    void stolenHolder_cannotRenew() throws InterruptedException {
        long stolen = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(1)).orElseThrow().fencingToken();
        Thread.sleep(2000);
        lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30)).orElseThrow();
        assertThat(lock().tryRenew(job, stolen, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("an expired lease cannot be renewed back to life even when no other pod has taken over")
    void renewAfterExpiry_isRejected() throws InterruptedException {
        long token = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(1)).orElseThrow().fencingToken();
        Thread.sleep(2000);

        assertThat(lock().tryRenew(job, token, Duration.ofSeconds(30)))
                .as("a lapsed lease must be re-acquired, not resurrected")
                .isFalse();
    }

    @Test
    @DisplayName("a lease that expired and was renewed away is still free for another pod to take")
    void renewAfterExpiry_leavesLockAcquirable() throws InterruptedException {
        long token = lock().tryAcquire(job, "pod-A", Duration.ofSeconds(1)).orElseThrow().fencingToken();
        Thread.sleep(2000);
        lock().tryRenew(job, token, Duration.ofSeconds(300));

        assertThat(lock().tryAcquire(job, "pod-B", Duration.ofSeconds(30)))
                .as("a failed renew must not extend the lease and block failover")
                .isPresent();
    }
}
