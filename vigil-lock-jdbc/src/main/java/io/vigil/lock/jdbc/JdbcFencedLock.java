package io.vigil.lock.jdbc;

import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcFencedLock implements FencedLock {

    private static final String SQL_SEED_ROW        = loadSql("seed-row.sql");
    private static final String SQL_ACQUIRE         = loadSql("acquire.sql");
    private static final String SQL_ACQUIRE_UPDATE  = loadSql("acquire-update.sql");
    private static final String SQL_RENEW           = loadSql("renew.sql");
    private static final String SQL_RELEASE         = loadSql("release.sql");

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;

    public JdbcFencedLock(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx   = tx;
    }

    @Override
    public void ensureSeedRow(String jobName) {
        Instant now = Instant.now();
        try {
            jdbc.update(SQL_SEED_ROW,
                    jobName,
                    UUID.randomUUID().toString(),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    jobName);
        } catch (DuplicateKeyException ignored) {
        }
    }

    @Override
    public Optional<LockAcquisition> tryAcquire(String jobName, String podId, Duration ttl) {
        return tx.execute(status -> {
            Instant now = Instant.now();
            List<Map<String, Object>> rows = jdbc.queryForList(SQL_ACQUIRE, jobName);

            if (rows.isEmpty()) {
                return Optional.<LockAcquisition>empty();
            }

            Map<String, Object> row = rows.getFirst();
            String currentStatus = (String) row.get("status");
            Timestamp expiresAt  = (Timestamp) row.get("expires_at");
            long currentToken    = ((Number) row.get("token")).longValue();
            String existingRunId = (String) row.get("run_id");

            boolean expired = expiresAt != null && expiresAt.toInstant().isBefore(now);
            if (!isAcquirable(currentStatus, expired)) {
                return Optional.<LockAcquisition>empty();
            }

            boolean isOrphanRecovery = !"FREE".equals(currentStatus) || expired;
            long newToken = currentToken + 1;
            String runId  = (isOrphanRecovery && existingRunId != null)
                    ? existingRunId
                    : UUID.randomUUID().toString();

            jdbc.update(SQL_ACQUIRE_UPDATE,
                    runId, podId, newToken,
                    Timestamp.from(now), Timestamp.from(now.plus(ttl)),
                    jobName);

            return Optional.of(new LockAcquisition(newToken, UUID.fromString(runId)));
        });
    }

    @Override
    public boolean tryRenew(String jobName, long fencingToken, Duration ttl) {
        int rows = jdbc.update(SQL_RENEW,
                Timestamp.from(Instant.now().plus(ttl)),
                jobName,
                fencingToken);
        return rows > 0;
    }

    @Override
    public void release(String jobName, long fencingToken) {
        jdbc.update(SQL_RELEASE, jobName, fencingToken);
    }

    private static boolean isAcquirable(String status, boolean expired) {
        if ("PAUSED".equals(status)) return false;
        return "FREE".equals(status) || "ORPHANED".equals(status) || expired;
    }

    private static String loadSql(String name) {
        String path = "/io/vigil/lock/jdbc/" + name;
        try (InputStream is = JdbcFencedLock.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("SQL file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SQL: " + name, e);
        }
    }
}
