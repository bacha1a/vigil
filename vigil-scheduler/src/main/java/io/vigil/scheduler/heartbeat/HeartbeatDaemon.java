package io.vigil.scheduler.heartbeat;

import io.vigil.core.spi.FencedLock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HeartbeatDaemon {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatDaemon.class);

    private final FencedLock fencedLock;
    private final ConcurrentHashMap<String, HeartbeatEntry> registry = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private Thread daemonThread;

    public HeartbeatDaemon(FencedLock fencedLock) {
        this.fencedLock = fencedLock;
    }

    @PostConstruct
    public void start() {
        daemonThread = Thread.ofPlatform().daemon(true).priority(Thread.MAX_PRIORITY)
                .name("vigil-lock-heartbeat").unstarted(this::loop);
        daemonThread.start();
    }

    @PreDestroy
    public void stop() {
        if (!running) return;
        running = false;
        if (daemonThread != null) {
            daemonThread.interrupt();
        }
        for (HeartbeatEntry entry : registry.values()) {
            fencedLock.release(entry.jobName(), entry.fencingToken());
        }
    }

    public void register(String jobName, long fencingToken, Thread jobThread, Duration ttl) {
        registry.put(jobName, new HeartbeatEntry(jobName, fencingToken, jobThread, ttl));
    }

    public void deregister(String jobName) {
        registry.remove(jobName);
    }

    private void loop() {
        while (running) {
            for (HeartbeatEntry entry : new ArrayList<>(registry.values())) {
                try {
                    boolean renewed = fencedLock.tryRenew(entry.jobName(), entry.fencingToken(), entry.ttl());
                    if (renewed) {
                        entry.markRenewed();
                    } else {
                        log.warn("[Vigil] Lock renewal failed for job '{}' token {} - stolen, interrupting job thread",
                                entry.jobName(), entry.fencingToken());
                        interruptAndRemove(entry);
                    }
                } catch (RuntimeException e) {
                    long sinceRenewMs = System.currentTimeMillis() - entry.lastRenewedAtMillis();
                    if (sinceRenewMs >= entry.ttl().toMillis()) {
                        log.error("[Vigil] Cannot renew lock for job '{}' token {} for {}ms (>= ttl {}ms) - "
                                        + "lease has expired, self-fencing and interrupting job thread",
                                entry.jobName(), entry.fencingToken(), sinceRenewMs, entry.ttl().toMillis());
                        interruptAndRemove(entry);
                    } else {
                        log.warn("[Vigil] Heartbeat tryRenew threw for job '{}' token {} ({}ms since last renew) "
                                        + "- will retry next tick",
                                entry.jobName(), entry.fencingToken(), sinceRenewMs, e);
                    }
                }
            }

            long sleepMs = registry.values().stream()
                    .mapToLong(e -> e.ttl().toMillis() / 3)
                    .min()
                    .orElse(10_000L);
            sleepMs = Math.max(sleepMs, 1000L);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void interruptAndRemove(HeartbeatEntry entry) {
        entry.jobThread().interrupt();
        registry.remove(entry.jobName());
    }
}

