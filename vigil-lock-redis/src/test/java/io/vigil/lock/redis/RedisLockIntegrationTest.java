package io.vigil.lock.redis;

import io.vigil.core.model.LockAcquisition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisLockIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisLock            lock;
    static StringRedisTemplate  redis;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        lock = new RedisLock(redis);
    }

    @BeforeEach
    void resetRedis() {
        Set<String> keys = redis.keys("vigil:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Nested
    @DisplayName("tryAcquire")
    class TryAcquire {

        @Test
        @DisplayName("returns token=1 and a non-null run ID when lock is free")
        void whenFree_returnsToken() {
            Optional<LockAcquisition> result = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));

            assertThat(result).isPresent();
            assertThat(result.get().fencingToken()).isEqualTo(1L);
            assertThat(result.get().runId()).isNotNull();
        }

        @Test
        @DisplayName("increments fencing token on each re-acquisition")
        void secondAcquisition_incrementsToken() {
            Optional<LockAcquisition> first = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(first).isPresent();
            lock.release("test-job", first.get().fencingToken());

            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(second).isPresent();
            assertThat(second.get().fencingToken()).isEqualTo(2L);
        }

        @Test
        @DisplayName("returns empty when another holder has the lock")
        void whenHeld_returnsEmpty() {
            Optional<LockAcquisition> first = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(first).isPresent();

            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-2", Duration.ofSeconds(60));
            assertThat(second).isEmpty();
        }

        @Test
        @DisplayName("succeeds and increments token after the previous holder's TTL expires")
        void whenExpired_succeeds() throws InterruptedException {
            Optional<LockAcquisition> first = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(2));
            assertThat(first).isPresent();

            Thread.sleep(3000);

            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-2", Duration.ofSeconds(60));
            assertThat(second).isPresent();
            assertThat(second.get().fencingToken()).isEqualTo(2L);
        }

        @Test
        @DisplayName("different jobs acquire independently and get distinct run IDs")
        void differentJobs_areIndependent() {
            Optional<LockAcquisition> a = lock.tryAcquire("job-alpha", "pod-1", Duration.ofSeconds(60));
            Optional<LockAcquisition> b = lock.tryAcquire("job-beta",  "pod-1", Duration.ofSeconds(60));

            assertThat(a).isPresent();
            assertThat(b).isPresent();
            assertThat(a.get().runId()).isNotEqualTo(b.get().runId());
        }

        @Test
        @DisplayName("each acquisition produces a unique run ID")
        void runId_isUniquePerAcquisition() {
            Optional<LockAcquisition> first = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(first).isPresent();
            lock.release("test-job", first.get().fencingToken());

            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(second).isPresent();

            assertThat(first.get().runId()).isNotEqualTo(second.get().runId());
        }

        @Test
        @DisplayName("under contention, exactly one thread wins per round across 50 rounds with 20 threads")
        void concurrent_onlyOneWinsPerRound() throws InterruptedException {
            int threads = 20;
            int rounds  = 50;
            int doubleAcquisitions = 0;

            for (int round = 0; round < rounds; round++) {
                resetRedis();

                CountDownLatch start  = new CountDownLatch(1);
                CountDownLatch done   = new CountDownLatch(threads);
                List<Long> tokens = Collections.synchronizedList(new ArrayList<>());

                ExecutorService executor = Executors.newFixedThreadPool(threads);
                for (int i = 0; i < threads; i++) {
                    final String podId = "pod-" + i;
                    executor.submit(() -> {
                        try {
                            start.await();
                            lock.tryAcquire("concurrent-job", podId, Duration.ofSeconds(30))
                                    .ifPresent(a -> tokens.add(a.fencingToken()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                done.await();
                executor.shutdown();

                if (tokens.size() > 1) doubleAcquisitions++;
            }

            assertThat(doubleAcquisitions).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("tryRenew")
    class TryRenew {

        @Test
        @DisplayName("returns true when the fencing token matches the current holder")
        void correctToken_returnsTrue() {
            Optional<LockAcquisition> acq = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(acq).isPresent();

            assertThat(lock.tryRenew("test-job", acq.get().fencingToken())).isTrue();
        }

        @Test
        @DisplayName("returns false when the token belongs to a previous holder")
        void staleToken_returnsFalse() {
            Optional<LockAcquisition> first = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(first).isPresent();
            long staleToken = first.get().fencingToken();

            lock.release("test-job", staleToken);
            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-2", Duration.ofSeconds(60));
            assertThat(second).isPresent();

            assertThat(lock.tryRenew("test-job", staleToken)).isFalse();
        }

        @Test
        @DisplayName("zombie pod cannot renew after TTL expiry and re-acquisition by another holder")
        void zombiePod_cannotRenewAfterReacquisition() throws InterruptedException {
            Optional<LockAcquisition> zombie = lock.tryAcquire("test-job", "zombie-pod", Duration.ofSeconds(2));
            assertThat(zombie).isPresent();
            long zombieToken = zombie.get().fencingToken();

            Thread.sleep(3000);

            Optional<LockAcquisition> newHolder = lock.tryAcquire("test-job", "pod-2", Duration.ofSeconds(60));
            assertThat(newHolder).isPresent();
            assertThat(newHolder.get().fencingToken()).isEqualTo(2L);

            assertThat(lock.tryRenew("test-job", zombieToken, Duration.ofSeconds(300))).isFalse();
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("frees the lock so another holder can acquire it")
        void freesLock_allowsReacquire() {
            Optional<LockAcquisition> first = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(first).isPresent();
            lock.release("test-job", first.get().fencingToken());

            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-2", Duration.ofSeconds(60));
            assertThat(second).isPresent();
        }
    }
}
