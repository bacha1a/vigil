package io.vigil.verify;

import io.vigil.core.model.LockAcquisition;
import io.vigil.lock.jdbc.JdbcFencedLock;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class PodRunner {

    static final int UNITS_PER_HOLD = 6;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "init"      -> init(args[1]);
            case "run"       -> run(args[1], args[2], args[3], args[4],
                                    Long.parseLong(args[5]), Long.parseLong(args[6]), Long.parseLong(args[7]));
            case "reconcile" -> reconcile(args[1], args[2], args[3]);
            default          -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    static JdbcTemplate jdbc(String url) {
        var ds = new DriverManagerDataSource();
        ds.setUrl(url);
        return new JdbcTemplate(ds);
    }

    static void init(String url) {
        JdbcTemplate jdbc = jdbc(url);
        jdbc.execute("DROP TABLE IF EXISTS work_log, acquisitions, comparison_ledger, vigil_job_locks, shedlock");
        jdbc.execute("CREATE TABLE vigil_job_locks (job_name VARCHAR(255) NOT NULL, run_id VARCHAR(36) NOT NULL, " +
                "holder VARCHAR(255) NOT NULL, token BIGINT NOT NULL DEFAULT 0, acquired_at TIMESTAMP NOT NULL, " +
                "expires_at TIMESTAMP NOT NULL, status VARCHAR(16) NOT NULL, " +
                "CONSTRAINT pk_vigil_job_locks PRIMARY KEY (job_name), " +
                "CONSTRAINT vigil_job_locks_status_check CHECK (status IN ('FREE','HELD','ORPHANED')))");
        jdbc.execute("CREATE TABLE shedlock (name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL, " +
                "locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL, PRIMARY KEY (name))");
        jdbc.execute("CREATE TABLE comparison_ledger (job_name VARCHAR(64) PRIMARY KEY, value BIGINT NOT NULL, guard_token BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE acquisitions (acq_id BIGSERIAL PRIMARY KEY, job_name VARCHAR(64), lib VARCHAR(16), pod VARCHAR(32), ts TIMESTAMPTZ DEFAULT now())");
        jdbc.execute("CREATE TABLE work_log (seq BIGSERIAL PRIMARY KEY, job_name VARCHAR(64), lib VARCHAR(16), pod VARCHAR(32), acq_id BIGINT, ts TIMESTAMPTZ DEFAULT now())");
        System.out.println("[init] schema ready");
    }

    static void run(String lib, String pod, String url, String job, long durationMs, long workMs, long leaseMs) throws InterruptedException {
        JdbcTemplate jdbc = jdbc(url);
        jdbc.update("INSERT INTO comparison_ledger (job_name, value, guard_token) VALUES (?,0,0) ON CONFLICT (job_name) DO NOTHING", job);
        long end = System.currentTimeMillis() + durationMs;
        if (lib.equals("vigil")) {
            runVigil(jdbc, url, pod, job, end, workMs, leaseMs);
        } else {
            runShedlock(jdbc, pod, job, end, workMs, leaseMs);
        }
        System.out.println("[" + lib + "/" + pod + "] done");
    }

    static void runVigil(JdbcTemplate jdbc, String url, String pod, String job, long end, long workMs, long leaseMs) throws InterruptedException {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcFencedLock lock = new JdbcFencedLock(jdbc, tx);
        lock.ensureSeedRow(job);
        while (System.currentTimeMillis() < end) {
            Optional<LockAcquisition> acq = lock.tryAcquire(job, pod, Duration.ofMillis(leaseMs));
            if (acq.isEmpty()) { Thread.sleep(30); continue; }
            long token = acq.get().fencingToken();
            long acqId = newAcquisition(jdbc, "vigil", pod, job);
            for (int u = 0; u < UNITS_PER_HOLD && System.currentTimeMillis() < end; u++) {
                Thread.sleep(workMs);
                int rows = jdbc.update("UPDATE comparison_ledger SET value = value + 1, guard_token = ? " +
                        "WHERE job_name = ? AND ? = (SELECT token FROM vigil_job_locks WHERE job_name = ?)",
                        token, job, token, job);
                if (rows == 0) break;
                jdbc.update("INSERT INTO work_log (job_name, lib, pod, acq_id) VALUES (?,?,?,?)", job, "vigil", pod, acqId);
            }
            lock.release(job, token);
        }
    }

    static void runShedlock(JdbcTemplate jdbc, String pod, String job, long end, long workMs, long leaseMs) throws InterruptedException {
        var provider = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbc).build());
        while (System.currentTimeMillis() < end) {
            Optional<SimpleLock> held = provider.lock(new LockConfiguration(Instant.now(), job, Duration.ofMillis(leaseMs), Duration.ZERO));
            if (held.isEmpty()) { Thread.sleep(30); continue; }
            long acqId = newAcquisition(jdbc, "shedlock", pod, job);
            for (int u = 0; u < UNITS_PER_HOLD && System.currentTimeMillis() < end; u++) {
                Thread.sleep(workMs);
                jdbc.update("UPDATE comparison_ledger SET value = value + 1 WHERE job_name = ?", job);
                jdbc.update("INSERT INTO work_log (job_name, lib, pod, acq_id) VALUES (?,?,?,?)", job, "shedlock", pod, acqId);
            }
            held.get().unlock();
        }
    }

    static long newAcquisition(JdbcTemplate jdbc, String lib, String pod, String job) {
        Long id = jdbc.queryForObject(
                "INSERT INTO acquisitions (job_name, lib, pod) VALUES (?,?,?) RETURNING acq_id", Long.class, job, lib, pod);
        return id == null ? 0L : id;
    }

    static void reconcile(String url, String job, String lib) {
        JdbcTemplate jdbc = jdbc(url);
        Long total = jdbc.queryForObject("SELECT count(*) FROM work_log WHERE job_name = ? AND lib = ?", Long.class, job, lib);
        Long violations = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT acq_id, max(acq_id) OVER (ORDER BY seq ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS run_max " +
                "FROM work_log WHERE job_name = ? AND lib = ?) t WHERE run_max IS NOT NULL AND acq_id < run_max",
                Long.class, job, lib);
        Long acquisitions = jdbc.queryForObject("SELECT count(*) FROM acquisitions WHERE job_name = ? AND lib = ?", Long.class, job, lib);
        System.out.printf("{\"lib\":\"%s\",\"acquisitions\":%d,\"committedUnits\":%d,\"violations\":%d}%n",
                lib, acquisitions, total, violations);
    }
}
