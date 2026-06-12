package io.vigil.scheduler.worker;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FencedJobWorkerRegistry {

    private static final int MAX_RECENT_DURATIONS = 10;

    private final ConcurrentHashMap<String, FencedJobWorker> workers         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Long>>      recentDurations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CronSpec>        cronSpecs       = new ConcurrentHashMap<>();
    private final Set<String>                                suspended       = ConcurrentHashMap.newKeySet();

    private record CronSpec(String expression, String zone) {}

    public void register(String jobName, FencedJobWorker worker) {
        workers.put(jobName, worker);
    }

    public boolean isRegistered(String jobName) {
        return workers.containsKey(jobName);
    }

    public void suspend(String jobName) { suspended.add(jobName); }
    public void resume(String jobName)  { suspended.remove(jobName); }
    public boolean isSuspended(String jobName) { return suspended.contains(jobName); }

    public void submitRecovery(String jobName) {
        if (suspended.contains(jobName)) return;
        FencedJobWorker worker = workers.get(jobName);
        if (worker != null) {
            Thread.ofPlatform().daemon(true).name("vigil-recovery-" + jobName).start(worker::run);
        }
    }

    public Set<String> registeredJobNames() {
        return workers.keySet();
    }

    public void recordDuration(String jobName, long durationMs) {
        recentDurations.compute(jobName, (k, old) -> {
            var list = new ArrayList<Long>(old != null ? old : List.of());
            list.add(durationMs);
            if (list.size() > MAX_RECENT_DURATIONS) {
                return new ArrayList<>(list.subList(list.size() - MAX_RECENT_DURATIONS, list.size()));
            }
            return list;
        });
    }

    public void registerCron(String jobName, String cron, String zone) {
        if (!cron.isBlank()) {
            cronSpecs.put(jobName, new CronSpec(cron, zone));
        }
    }

    public Optional<Instant> nextTriggerAt(String jobName) {
        var spec = cronSpecs.get(jobName);
        if (spec == null) return Optional.empty();
        var next = CronExpression.parse(spec.expression()).next(ZonedDateTime.now(ZoneId.of(spec.zone())));
        return next == null ? Optional.empty() : Optional.of(next.toInstant());
    }

    public OptionalLong peakDurationMs(String jobName) {
        var snapshot = recentDurations.get(jobName);
        if (snapshot == null || snapshot.isEmpty()) return OptionalLong.empty();
        return snapshot.stream().mapToLong(Long::longValue).max();
    }
}
