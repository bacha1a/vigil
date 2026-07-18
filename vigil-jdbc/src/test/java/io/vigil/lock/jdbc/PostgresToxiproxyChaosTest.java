package io.vigil.lock.jdbc;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.vigil.core.model.LockAcquisition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresToxiproxyChaosTest {

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(NETWORK);

    static Proxy proxy;
    static JdbcFencedLock lock;
    static JdbcFencedLock laggyLock;
    static JdbcFencedLock directLock;
    static JdbcTemplate jdbc;
    static String proxiedUrl;
    static SingleConnectionDataSource laggyDs;

    private static JdbcFencedLock lockOver(javax.sql.DataSource ds) {
        return new JdbcFencedLock(new JdbcTemplate(ds),
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @BeforeAll
    static void setUp() throws IOException {
        ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        proxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");

        proxiedUrl = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666)
                + "/" + POSTGRES.getDatabaseName();
        String url = proxiedUrl + "?connectTimeout=2&socketTimeout=2";

        DriverManagerDataSource ds = new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        lock = new JdbcFencedLock(jdbc, tx);

        directLock = lockOver(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

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
    void resetState() throws IOException {
        proxy.enable();
        removeAllToxics();
        lock.ensureSeedRow("chaos-job");
        jdbc.update("UPDATE vigil_job_locks SET status = 'FREE', token = 0 WHERE job_name = 'chaos-job'");

        laggyDs = new SingleConnectionDataSource(
                proxiedUrl + "?connectTimeout=30&socketTimeout=30",
                POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        laggyDs.setAutoCommit(true);
        laggyLock = lockOver(laggyDs);
        new JdbcTemplate(laggyDs).queryForObject("SELECT 1", Integer.class);
    }

    @AfterEach
    void restoreConnection() throws IOException {
        proxy.enable();
        removeAllToxics();
        if (laggyDs != null) {
            laggyDs.destroy();
        }
    }

    private static void removeAllToxics() throws IOException {
        for (Toxic toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    @Test
    @DisplayName("a holder cannot renew its lease while the database connection is cut")
    void renewDuringConnectionCut_throws() throws IOException {
        Optional<LockAcquisition> acq = lock.tryAcquire("chaos-job", "pod-1", Duration.ofSeconds(60));
        assertThat(acq).isPresent();

        proxy.disable();

        assertThatThrownBy(() -> lock.tryRenew("chaos-job", acq.get().fencingToken()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a slow renew still in flight when another pod takes over is fenced by its stale token")
    void slowRenewLandingAfterTakeover_isFenced() throws Exception {
        LockAcquisition pod1 = laggyLock.tryAcquire("chaos-job", "pod-1", Duration.ofSeconds(3)).orElseThrow();
        assertThat(pod1.fencingToken()).isEqualTo(1L);

        proxy.toxics().latency("slow-upstream", ToxicDirection.UPSTREAM, 6000);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> lateRenew = pool.submit(() ->
                    laggyLock.tryRenew("chaos-job", pod1.fencingToken(), Duration.ofSeconds(300)));

            Thread.sleep(3500);
            LockAcquisition pod2 = directLock.tryAcquire("chaos-job", "pod-2", Duration.ofSeconds(60)).orElseThrow();
            assertThat(pod2.fencingToken()).isEqualTo(2L);

            assertThat(lateRenew.get(30, TimeUnit.SECONDS))
                    .as("an in-flight renew must not resurrect a lease pod-2 already took over")
                    .isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a slow renew that lands after its lease expired is rejected even when no pod has taken over")
    void slowRenewAfterLeaseExpiry_isRejected() throws Exception {
        LockAcquisition pod1 = laggyLock.tryAcquire("chaos-job", "pod-1", Duration.ofSeconds(3)).orElseThrow();

        proxy.toxics().latency("slow-upstream", ToxicDirection.UPSTREAM, 6000);

        boolean renewed = laggyLock.tryRenew("chaos-job", pod1.fencingToken(), Duration.ofSeconds(300));

        assertThat(renewed)
                .as("a lease that already lapsed cannot be renewed; the holder must re-acquire")
                .isFalse();
    }

    @Test
    @DisplayName("a partitioned holder that reconnects after failover is fenced out by its stale token")
    void zombieReconnectsAfterFailover_isFencedOut() throws IOException, InterruptedException {
        Optional<LockAcquisition> zombie = lock.tryAcquire("chaos-job", "pod-1", Duration.ofSeconds(3));
        assertThat(zombie).isPresent();
        long zombieToken = zombie.get().fencingToken();

        proxy.disable();
        Thread.sleep(4000);
        proxy.enable();

        Optional<LockAcquisition> newHolder = lock.tryAcquire("chaos-job", "pod-2", Duration.ofSeconds(60));
        assertThat(newHolder).isPresent();
        assertThat(newHolder.get().fencingToken()).isEqualTo(2L);

        assertThat(lock.tryRenew("chaos-job", zombieToken, Duration.ofSeconds(300))).isFalse();
    }
}
