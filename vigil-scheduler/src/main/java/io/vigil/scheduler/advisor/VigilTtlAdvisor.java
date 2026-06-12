package io.vigil.scheduler.advisor;

import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VigilTtlAdvisor {

    private static final Logger log = LoggerFactory.getLogger(VigilTtlAdvisor.class);

    private final FencedJobWorkerRegistry registry;
    public final Set<String>              warnedJobs = ConcurrentHashMap.newKeySet();

    public VigilTtlAdvisor(FencedJobWorkerRegistry registry) {
        this.registry = registry;
    }

    public void onRunCompleted(String jobName, long durationMs, int lockTtlSeconds) {
        registry.recordDuration(jobName, durationMs);
        long halfTtlMs = (long) lockTtlSeconds * 500L;
        registry.peakDurationMs(jobName).ifPresent(peak -> {
            if (peak > halfTtlMs && warnedJobs.add(jobName)) {
                log.warn("[Vigil] Job '{}' peak recent runtime is {}ms but lockTtlSeconds={}s" +
                         " - this exceeds half the TTL. Recommended: lockTtlSeconds >= {}s" +
                         " to avoid GC split-brain risk.",
                        jobName, peak, lockTtlSeconds, (peak * 2 / 1000) + 1);
            }
        });
    }
}
