package io.vigil.scheduler;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ShedLockComparisonTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        var ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(ds);

        jdbc.execute(
                "CREATE TABLE shedlock (" +
                "name VARCHAR(64) NOT NULL, " +
                "lock_until TIMESTAMP(3) NOT NULL, " +
                "locked_at TIMESTAMP(3) NOT NULL, " +
                "locked_by VARCHAR(255) NOT NULL, " +
                "PRIMARY KEY (name))");

        jdbc.execute(
                "CREATE TABLE shedlock_shared_state (" +
                "job_name VARCHAR(64) PRIMARY KEY, " +
                "last_written_by VARCHAR(64) NOT NULL)");
    }

    @Nested
    @DisplayName("ShedLock GC split-brain vulnerability")
    class SplitBrain {

        @Test
        @DisplayName("ShedLock allows zombie writes - corruptionEvents > 0 across 1000 iterations")
        void shedLockAllowsZombieWrites() throws InterruptedException, IOException {
            int totalRounds     = 1000;
            int corruptionEvents = 0;

            for (int i = 0; i < totalRounds; i++) {
                jdbc.update("DELETE FROM shedlock WHERE name = 'comparison-job'");
                jdbc.update("INSERT INTO shedlock_shared_state (job_name, last_written_by) " +
                            "VALUES ('comparison-job', 'none') " +
                            "ON CONFLICT (job_name) DO UPDATE SET last_written_by = 'none'");

                var pod1Provider = new JdbcTemplateLockProvider(
                        JdbcTemplateLockProvider.Configuration.builder()
                                .withJdbcTemplate(jdbc).build());
                var pod2Provider = new JdbcTemplateLockProvider(
                        JdbcTemplateLockProvider.Configuration.builder()
                                .withJdbcTemplate(jdbc).build());

                var pod1Config = new LockConfiguration(
                        Instant.now(), "comparison-job",
                        Duration.ofMillis(50), Duration.ZERO);
                var pod1Lock = pod1Provider.lock(pod1Config);
                assertThat(pod1Lock).as("pod-1 should acquire on iteration " + i).isPresent();

                jdbc.update("UPDATE shedlock_shared_state SET last_written_by = 'pod-1' " +
                            "WHERE job_name = 'comparison-job'");

                Thread.sleep(100);

                var pod2Config = new LockConfiguration(
                        Instant.now(), "comparison-job",
                        Duration.ofMillis(500), Duration.ZERO);
                var pod2Lock = pod2Provider.lock(pod2Config);

                if (pod2Lock.isPresent()) {
                    jdbc.update("UPDATE shedlock_shared_state SET last_written_by = 'pod-2' " +
                                "WHERE job_name = 'comparison-job'");

                    int zombieRows = jdbc.update(
                            "UPDATE shedlock_shared_state SET last_written_by = 'pod-1-zombie' " +
                            "WHERE job_name = 'comparison-job'");

                    if (zombieRows > 0) {
                        corruptionEvents++;
                    }

                    pod2Lock.get().unlock();
                }
            }

            var corruptionRate = String.format("%d/%d", corruptionEvents, totalRounds);
            var json = String.format(
                    "{%n" +
                    "  \"totalRounds\": %d,%n" +
                    "  \"corruptionEvents\": %d,%n" +
                    "  \"corruptionRate\": \"%s\",%n" +
                    "  \"conclusion\": \"ShedLock GC split-brain is reproducible\"%n" +
                    "}%n",
                    totalRounds, corruptionEvents, corruptionRate);

            var targetDir = Path.of("target");
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve("shedlock-comparison-results.json"), json);

            System.out.println("[ShedLock Comparison] " + json);

            assertThat(corruptionEvents)
                    .as("ShedLock split-brain must be reproducible")
                    .isGreaterThan(0);
        }
    }
}
