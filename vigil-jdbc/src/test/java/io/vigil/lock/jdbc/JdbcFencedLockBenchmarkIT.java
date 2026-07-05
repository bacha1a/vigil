package io.vigil.lock.jdbc;

import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcFencedLockBenchmarkIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcFencedLock lock;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        lock = new JdbcFencedLock(jdbc, tx);

        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS vigil_job_locks (" +
                "job_name VARCHAR(255) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, " +
                "holder VARCHAR(255) NOT NULL, token BIGINT NOT NULL DEFAULT 0, " +
                "acquired_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, " +
                "status VARCHAR(16) NOT NULL)");

        lock.ensureSeedRow("bench-job");
    }

    @Test
    void acquireReleaseThroughput() {
        int iterations = 50;
        var timings = new ArrayList<Long>(iterations);

        for (int i = 0; i < iterations; i++) {
            long start = System.currentTimeMillis();
            var acq = lock.tryAcquire("bench-job", "bench-pod", Duration.ofSeconds(30));
            acq.ifPresent(a -> lock.release("bench-job", a.fencingToken()));
            timings.add(System.currentTimeMillis() - start);
        }

        long total = timings.stream().mapToLong(Long::longValue).sum();
        long min   = timings.stream().mapToLong(Long::longValue).min().orElse(0);
        long max   = timings.stream().mapToLong(Long::longValue).max().orElse(0);
        long avg   = total / iterations;

        System.out.printf("[Vigil Benchmark] acquire+release over %d iterations: " +
                "avg=%dms  min=%dms  max=%dms%n", iterations, avg, min, max);

        assertThat(avg).as("average acquire+release overhead").isLessThan(500);
    }
}
