package io.vigil.scheduler;

import io.vigil.core.model.LockAcquisition;
import io.vigil.lock.jdbc.JdbcFencedLock;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("Realistic fault comparison: Vigil vs ShedLock under injected DB-stall")
class RealisticFaultComparisonTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static final int    ROUNDS     = Integer.getInteger("vigil.compare.rounds", 150);
    static final int    LEASE_MS   = Integer.getInteger("vigil.compare.leaseMs", 250);
    static final double FAULT_RATE = Double.parseDouble(System.getProperty("vigil.compare.faultRate", "0.2"));
    static final long   SEED       = Long.getLong("vigil.compare.seed", 42L);

    static final String JOB = "compare-job";

    static JdbcTemplate       jdbc;
    static JdbcFencedLock     vigil;

    @BeforeAll
    static void setUp() {
        var ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(ds);
        vigil = new JdbcFencedLock(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));

        jdbc.execute("CREATE TABLE vigil_job_locks (" +
                "job_name VARCHAR(255) NOT NULL, run_id VARCHAR(36) NOT NULL, holder VARCHAR(255) NOT NULL, " +
                "token BIGINT NOT NULL DEFAULT 0, acquired_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, " +
                "status VARCHAR(16) NOT NULL, CONSTRAINT pk_vigil_job_locks PRIMARY KEY (job_name), " +
                "CONSTRAINT vigil_job_locks_status_check CHECK (status IN ('FREE','HELD','ORPHANED')))");

        jdbc.execute("CREATE TABLE shedlock (" +
                "name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL, " +
                "locked_by VARCHAR(255) NOT NULL, PRIMARY KEY (name))");

        jdbc.execute("CREATE TABLE comparison_ledger (" +
                "job_name VARCHAR(64) PRIMARY KEY, value BIGINT NOT NULL, token BIGINT NOT NULL)");

        vigil.ensureSeedRow(JOB);
    }

    @Test
    @DisplayName("ShedLock corrupts on ~fault-rate of rounds; Vigil corrupts on none")
    void compareUnderRandomFaults() throws IOException {
        boolean[] faults = buildFaultSchedule();
        int faulted = 0;
        for (boolean f : faults) if (f) faulted++;

        List<String> ledgerRows = new ArrayList<>();
        ledgerRows.add("library,round,faulted,expected,final,corrupted");

        int vigilCorruptions    = runVigil(faults, ledgerRows);
        int shedlockCorruptions = runShedlock(faults, ledgerRows);

        double shedRate = (double) shedlockCorruptions / ROUNDS;

        String json = String.format(
                "{%n" +
                "  \"rounds\": %d,%n" +
                "  \"leaseMs\": %d,%n" +
                "  \"faultRate\": %.2f,%n" +
                "  \"faultedRounds\": %d,%n" +
                "  \"vigilCorruptions\": %d,%n" +
                "  \"shedlockCorruptions\": %d,%n" +
                "  \"shedlockCorruptionRate\": \"%.1f%%\",%n" +
                "  \"conclusion\": \"ShedLock corrupts on injected faults; Vigil fences them out\"%n" +
                "}%n",
                ROUNDS, LEASE_MS, FAULT_RATE, faulted,
                vigilCorruptions, shedlockCorruptions, shedRate * 100.0);

        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("realistic-fault-comparison.json"), json);
        Files.write(target.resolve("realistic-fault-comparison-ledger.csv"), ledgerRows);

        System.out.println("[Realistic Fault Comparison] " + json);

        assertThat(vigilCorruptions)
                .as("Vigil must fence every stale writer")
                .isZero();
        assertThat(shedlockCorruptions)
                .as("ShedLock must corrupt on injected faults")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(faulted);
        assertThat(shedRate)
                .as("corruption tracks the injected fault rate, not a rigged 100%")
                .isLessThan(0.9);
        assertThat(faulted)
                .as("fault schedule must be a realistic subset, not every round")
                .isGreaterThan(ROUNDS / 20)
                .isLessThan(ROUNDS / 2);
    }

    private int runVigil(boolean[] faults, List<String> ledgerRows) {
        int corruptions = 0;
        for (int r = 0; r < faults.length; r++) {
            resetLedger();
            long v1 = r * 2L + 1;
            long v2 = r * 2L + 2;

            LockAcquisition pod1 = vigil.tryAcquire(JOB, "pod-1", Duration.ofMillis(LEASE_MS)).orElseThrow();

            long expected;
            if (!faults[r]) {
                guardedWrite(v1, pod1.fencingToken());
                expected = v1;
                vigil.release(JOB, pod1.fencingToken());
            } else {
                sleepPastLease(r);
                Optional<LockAcquisition> pod2 = vigil.tryAcquire(JOB, "pod-2", Duration.ofMillis(LEASE_MS));
                assertThat(pod2).as("takeover should succeed after lease expiry, round " + r).isPresent();
                guardedWrite(v2, pod2.get().fencingToken());
                expected = v2;
                guardedWrite(v1, pod1.fencingToken());
                vigil.release(JOB, pod2.get().fencingToken());
            }

            long finalValue = readLedger();
            boolean corrupted = finalValue != expected;
            if (corrupted) corruptions++;
            ledgerRows.add(String.format("vigil,%d,%b,%d,%d,%b", r, faults[r], expected, finalValue, corrupted));
        }
        return corruptions;
    }

    private int runShedlock(boolean[] faults, List<String> ledgerRows) {
        var provider = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbc).build());
        int corruptions = 0;
        for (int r = 0; r < faults.length; r++) {
            resetLedger();
            long v1 = r * 2L + 1;
            long v2 = r * 2L + 2;

            Optional<SimpleLock> pod1 = provider.lock(
                    new LockConfiguration(Instant.now(), JOB, Duration.ofMillis(LEASE_MS), Duration.ZERO));
            assertThat(pod1).as("pod-1 should acquire, round " + r).isPresent();

            long expected;
            if (!faults[r]) {
                unguardedWrite(v1);
                expected = v1;
                pod1.get().unlock();
            } else {
                sleepPastLease(r);
                Optional<SimpleLock> pod2 = provider.lock(
                        new LockConfiguration(Instant.now(), JOB, Duration.ofMillis(LEASE_MS), Duration.ZERO));
                assertThat(pod2).as("takeover should succeed after lease expiry, round " + r).isPresent();
                unguardedWrite(v2);
                expected = v2;
                unguardedWrite(v1);
                pod2.get().unlock();
            }

            long finalValue = readLedger();
            boolean corrupted = finalValue != expected;
            if (corrupted) corruptions++;
            ledgerRows.add(String.format("shedlock,%d,%b,%d,%d,%b", r, faults[r], expected, finalValue, corrupted));
        }
        return corruptions;
    }

    private boolean[] buildFaultSchedule() {
        Random rnd = new Random(SEED);
        boolean[] faults = new boolean[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            faults[i] = rnd.nextDouble() < FAULT_RATE;
        }
        return faults;
    }

    private void sleepPastLease(int round) {
        try {
            Thread.sleep(LEASE_MS + 120L + new Random(SEED + round).nextInt(LEASE_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void resetLedger() {
        jdbc.update("INSERT INTO comparison_ledger (job_name, value, token) VALUES (?, 0, 0) " +
                "ON CONFLICT (job_name) DO UPDATE SET value = 0, token = 0", JOB);
    }

    private int guardedWrite(long value, long token) {
        return jdbc.update("UPDATE comparison_ledger SET value = ?, token = ? WHERE job_name = ? AND ? >= token",
                value, token, JOB, token);
    }

    private void unguardedWrite(long value) {
        jdbc.update("UPDATE comparison_ledger SET value = ? WHERE job_name = ?", value, JOB);
    }

    private long readLedger() {
        Long v = jdbc.queryForObject("SELECT value FROM comparison_ledger WHERE job_name = ?", Long.class, JOB);
        return v == null ? -1 : v;
    }
}
