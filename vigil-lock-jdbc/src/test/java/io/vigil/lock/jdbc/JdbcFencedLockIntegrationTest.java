package io.vigil.lock.jdbc;

import io.vigil.core.model.LockAcquisition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcFencedLockIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcFencedLock lock;
    static JdbcTemplate   jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        lock = new JdbcFencedLock(jdbc, tx);

        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS vigil_job_locks (" +
                "job_name    VARCHAR(255) NOT NULL, " +
                "run_id      VARCHAR(36)  NOT NULL, " +
                "holder      VARCHAR(255) NOT NULL, " +
                "token       BIGINT       NOT NULL DEFAULT 0, " +
                "acquired_at TIMESTAMP    NOT NULL, " +
                "expires_at  TIMESTAMP    NOT NULL, " +
                "status      VARCHAR(16)  NOT NULL, " +
                "CONSTRAINT pk_vigil_job_locks PRIMARY KEY (job_name)" +
                ")");
    }

    @BeforeEach
    void resetLock() {
        lock.ensureSeedRow("test-job");
        jdbc.update("UPDATE vigil_job_locks SET status = 'FREE', token = 0 WHERE job_name = 'test-job'");
    }

    @Nested
    @DisplayName("ensureSeedRow")
    class EnsureSeedRow {

        @Test
        @DisplayName("calling twice is idempotent - lock is acquirable after both calls")
        void calledTwice_isIdempotent() {
            lock.ensureSeedRow("idempotent-job");
            lock.ensureSeedRow("idempotent-job");

            Optional<LockAcquisition> acq = lock.tryAcquire("idempotent-job", "pod-1", Duration.ofSeconds(60));
            assertThat(acq).isPresent();
            assertThat(acq.get().fencingToken()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("tryAcquire")
    class TryAcquire {

        @Test
        @DisplayName("returns token=1 and a non-null run ID when lock is free")
        void whenFree_returnsTokenAndRunId() {
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
            lock.ensureSeedRow("job-alpha");
            lock.ensureSeedRow("job-beta");

            Optional<LockAcquisition> alpha = lock.tryAcquire("job-alpha", "pod-1", Duration.ofSeconds(60));
            Optional<LockAcquisition> beta  = lock.tryAcquire("job-beta",  "pod-1", Duration.ofSeconds(60));

            assertThat(alpha).isPresent();
            assertThat(beta).isPresent();
            assertThat(alpha.get().runId()).isNotEqualTo(beta.get().runId());
        }

        @Test
        @DisplayName("under contention, exactly one thread wins per round across 200 rounds with 50 threads")
        void concurrent_onlyOneWinsPerRound() throws InterruptedException {
            lock.ensureSeedRow("concurrent-job");

            int rounds = 200;
            int threads = 50;
            int doubleAcquisitionRounds = 0;

            for (int round = 0; round < rounds; round++) {
                jdbc.update("UPDATE vigil_job_locks SET status = 'FREE', token = 0 WHERE job_name = 'concurrent-job'");

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch  = new CountDownLatch(threads);
                List<Long> acquiredTokens = Collections.synchronizedList(new ArrayList<>());

                ExecutorService executor = Executors.newFixedThreadPool(threads);
                for (int i = 0; i < threads; i++) {
                    final String podId = "pod-" + i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            Optional<LockAcquisition> acq = lock.tryAcquire(
                                    "concurrent-job", podId, Duration.ofSeconds(30));
                            acq.ifPresent(a -> acquiredTokens.add(a.fencingToken()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                doneLatch.await();
                executor.shutdown();

                if (acquiredTokens.size() > 1) {
                    doubleAcquisitionRounds++;
                }
            }

            assertThat(doubleAcquisitionRounds)
                    .as("zero rounds should have more than one winner")
                    .isEqualTo(0);
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

        @Test
        @DisplayName("releasing with wrong token is a no-op - lock remains held")
        void wrongToken_isNoOp() {
            Optional<LockAcquisition> acq = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(acq).isPresent();

            lock.release("test-job", acq.get().fencingToken() + 999);

            Optional<LockAcquisition> second = lock.tryAcquire("test-job", "pod-2", Duration.ofSeconds(60));
            assertThat(second).isEmpty();
        }

        @Test
        @DisplayName("release on a PAUSED row does not flip status back to FREE")
        void releaseOnPausedRow_doesNotUndoPause() {
            Optional<LockAcquisition> acq = lock.tryAcquire("test-job", "pod-1", Duration.ofSeconds(60));
            assertThat(acq).isPresent();
            long token = acq.get().fencingToken();

            jdbc.update("UPDATE vigil_job_locks SET status = 'PAUSED' WHERE job_name = 'test-job'");

            lock.release("test-job", token);

            String status = jdbc.queryForObject(
                    "SELECT status FROM vigil_job_locks WHERE job_name = 'test-job'", String.class);
            assertThat(status).isEqualTo("PAUSED");
        }
    }
}
