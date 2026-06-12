package io.vigil.actuator.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vigil.scheduler.VigilJobLifecycleListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class VigilMetrics implements VigilJobLifecycleListener {

    private final MeterRegistry registry;

    public VigilMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onLockSkipped(String jobName) {
        Counter.builder("vigil.lock.acquisitions.total")
                .tags("job", jobName, "result", "skipped")
                .register(registry).increment();
    }

    @Override
    public void onLockAcquired(String jobName) {
        Counter.builder("vigil.lock.acquisitions.total")
                .tags("job", jobName, "result", "acquired")
                .register(registry).increment();
    }

    @Override
    public void onJobCompleted(String jobName, String status, long durationMs) {
        Counter.builder("vigil.job.executions.total")
                .tags("job", jobName, "result", status)
                .register(registry).increment();
        if ("success".equals(status)) {
            Timer.builder("vigil.job.duration.seconds")
                    .tags("job", jobName)
                    .register(registry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    @Override
    public void onLockStolen(String jobName) {
        Counter.builder("vigil.lock.stolen.total")
                .tags("job", jobName)
                .register(registry).increment();
    }

    @Override
    public void onItemProcessed(String jobName) {
        Counter.builder("vigil.job.items.processed.total")
                .tags("job", jobName)
                .register(registry).increment();
    }

    @Override
    public void onCheckpointSaved(String jobName) {
        Counter.builder("vigil.checkpoint.saves.total")
                .tags("job", jobName)
                .register(registry).increment();
    }

    @Override
    public void onFailoverRecovery(String jobName) {
        Counter.builder("vigil.failover.recoveries.total")
                .tags("job", jobName)
                .register(registry).increment();
    }
}
