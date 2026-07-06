package io.vigil.lock.jdbc;

import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import io.vigil.jdbc.SqlDialect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcFencedLock implements FencedLock {

    private static final String BASE = "/io/vigil/lock/jdbc";

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;

    private final String sqlSeedRow;
    private final String sqlAcquire;
    private final String sqlAcquireRead;
    private final String sqlRenew;
    private final String sqlRelease;

    public JdbcFencedLock(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx   = tx;

        SqlDialect dialect = SqlDialect.detect(jdbc);
        this.sqlSeedRow     = dialect.load(BASE, "seed-row.sql");
        this.sqlAcquire     = dialect.load(BASE, "acquire.sql");
        this.sqlAcquireRead = dialect.load(BASE, "acquire-read.sql");
        this.sqlRenew       = dialect.load(BASE, "renew.sql");
        this.sqlRelease     = dialect.load(BASE, "release.sql");
    }

    @Override
    public void ensureSeedRow(String jobName) {
        Instant now = Instant.now();
        try {
            jdbc.update(sqlSeedRow,
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
            String candidateRunId = UUID.randomUUID().toString();

            int updated = jdbc.update(sqlAcquire,
                    podId,
                    candidateRunId,
                    (int) ttl.getSeconds(),
                    jobName);

            if (updated == 0) {
                return Optional.<LockAcquisition>empty();
            }

            Map<String, Object> row = jdbc.queryForMap(sqlAcquireRead, jobName);
            long token  = ((Number) row.get("token")).longValue();
            UUID  runId = UUID.fromString((String) row.get("run_id"));
            return Optional.of(new LockAcquisition(token, runId));
        });
    }

    @Override
    public boolean tryRenew(String jobName, long fencingToken, Duration ttl) {
        int rows = jdbc.update(sqlRenew,
                (int) ttl.getSeconds(),
                jobName,
                fencingToken);
        return rows > 0;
    }

    @Override
    public void release(String jobName, long fencingToken) {
        jdbc.update(sqlRelease, jobName, fencingToken);
    }

    @Override
    public void checkConnectivity() {
        Boolean valid = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> connection.isValid(2));
        if (valid == null || !valid) {
            throw new IllegalStateException("JDBC connection is not valid");
        }
    }
}
