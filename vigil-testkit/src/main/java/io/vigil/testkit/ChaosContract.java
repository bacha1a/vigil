package io.vigil.testkit;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Chaos contract (contention + fencing under load)")
public abstract class ChaosContract {

    protected abstract FencedLock lock();

    protected abstract CheckpointManager checkpoints();

    private static final int THREADS = 16;

    @Test
    @DisplayName("under thread contention exactly one holder wins each round and fencing tokens strictly increase")
    void contendedAcquire_isMutuallyExclusiveAndMonotonic() throws InterruptedException {
        String job = "chaos-" + UUID.randomUUID();
        lock().ensureSeedRow(job);

        long previousToken = 0;
        for (int round = 0; round < 20; round++) {
            List<LockAcquisition> winners = raceToAcquire(job);

            assertThat(winners)
                    .as("exactly one holder per round (round %d)", round)
                    .hasSize(1);

            long token = winners.get(0).fencingToken();
            assertThat(token)
                    .as("fencing token strictly increases (round %d)", round)
                    .isGreaterThan(previousToken);
            previousToken = token;

            lock().release(job, token);
        }
    }

    @Test
    @DisplayName("after failover a fenced-out zombie writer never commits a checkpoint, no matter how many times it retries")
    void zombieWriterStorm_neverCommitsAfterFenced() throws InterruptedException {
        String job = "chaos-" + UUID.randomUUID();
        lock().ensureSeedRow(job);

        LockAcquisition zombie = lock().tryAcquire(job, "pod-zombie", Duration.ofSeconds(1)).orElseThrow();
        Thread.sleep(1500);
        LockAcquisition winner = lock().tryAcquire(job, "pod-winner", Duration.ofSeconds(60)).orElseThrow();
        assertThat(winner.fencingToken()).isGreaterThan(zombie.fencingToken());

        UUID runId = winner.runId();
        String stage = "STAGE";
        AtomicInteger zombieRejections = new AtomicInteger();
        AtomicInteger zombieAccepted = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx % 2 == 0) {
                        try {
                            checkpoints().save(entry(job, runId, stage,
                                    "\"zombie-" + idx + "\"", zombie.fencingToken()));
                            zombieAccepted.incrementAndGet();
                        } catch (LockStolenException expected) {
                            zombieRejections.incrementAndGet();
                        }
                    } else {
                        checkpoints().save(entry(job, runId, stage,
                                "\"winner\"", winner.fencingToken()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdownNow();

        assertThat(zombieAccepted.get())
                .as("no fenced-out write is ever accepted")
                .isZero();
        assertThat(zombieRejections.get())
                .as("every zombie write is rejected with LockStolenException")
                .isEqualTo(THREADS / 2);

        CheckpointEntry committed = checkpoints().load(job, runId, stage).orElseThrow();
        assertThat(committed.storedValue())
                .as("only the fenced winner's value is ever committed")
                .isEqualTo("\"winner\"");
        assertThat(committed.fencingToken()).isEqualTo(winner.fencingToken());
    }

    private List<LockAcquisition> raceToAcquire(String job) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<LockAcquisition> winners = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREADS; i++) {
            final String pod = "pod-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    Optional<LockAcquisition> got = lock().tryAcquire(job, pod, Duration.ofSeconds(30));
                    got.ifPresent(winners::add);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdownNow();
        return winners;
    }

    private static CheckpointEntry entry(String job, UUID runId, String stage, String value, long token) {
        return new CheckpointEntry(job, runId, stage, CheckpointStatus.COMPLETE, value, String.class.getName(), token);
    }
}
