package io.vigil.scheduler.worker;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.LockAcquisition;
import io.vigil.scheduler.FencedScheduled;
import io.vigil.scheduler.JobContext;
import io.vigil.scheduler.VigilJobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

public class FencedJobWorker {

    private static final Logger log = LoggerFactory.getLogger(FencedJobWorker.class);

    private enum RunOutcome { SUCCESS, FAILED, STOLEN }

    private final String                 jobName;
    private final int                    lockTtlSeconds;
    private final boolean                warnOnSkip;
    private final Object                 bean;
    private final Method                 method;
    private final Consumer<JobContext>   programmaticAction;
    private final WorkerDeps             deps;

    private FencedJobWorker(String jobName, int lockTtlSeconds, boolean warnOnSkip,
                            Object bean, Method method, Consumer<JobContext> programmaticAction,
                            WorkerDeps deps) {
        this.jobName            = jobName;
        this.lockTtlSeconds     = lockTtlSeconds;
        this.warnOnSkip         = warnOnSkip;
        this.bean               = bean;
        this.method             = method;
        this.programmaticAction = programmaticAction;
        this.deps               = deps;
    }

    public static FencedJobWorker forAnnotatedMethod(FencedScheduled config, Object bean,
                                                     Method method, WorkerDeps deps) {
        method.setAccessible(true);
        return new FencedJobWorker(config.name(), config.lockTtlSeconds(), config.warnOnSkip(),
                bean, method, null, deps);
    }

    public static FencedJobWorker forDefinition(VigilJobDefinition defn, WorkerDeps deps) {
        return new FencedJobWorker(defn.name(), (int) defn.lockTtlSeconds(), defn.warnOnSkip(),
                null, null, defn.action(), deps);
    }

    public void run() {
        var ttl      = Duration.ofSeconds(lockTtlSeconds);
        var acquired = deps.fencedLock().tryAcquire(jobName, deps.podId(), ttl);
        if (acquired.isEmpty()) {
            logSkip();
            if (deps.listener() != null) deps.listener().onLockSkipped(jobName);
            return;
        }
        if (deps.listener() != null) deps.listener().onLockAcquired(jobName);
        execute(acquired.get(), ttl);
    }

    private void logSkip() {
        if (warnOnSkip) {
            log.info("[Vigil] Job '{}' skipped - lock held by another pod", jobName);
        } else {
            log.trace("[Vigil] Job '{}' skipped - lock held", jobName);
        }
    }

    private void execute(LockAcquisition acquisition, Duration ttl) {
        deps.heartbeatDaemon().register(jobName, acquisition.fencingToken(), Thread.currentThread(), ttl);
        var        ctx      = hasJobContext() ? bindContext(acquisition) : null;
        RunOutcome outcome  = RunOutcome.FAILED;
        String     errorMsg = null;
        long       start    = System.currentTimeMillis();
        long       elapsed  = 0L;
        if (deps.recorder() != null) {
            deps.recorder().startRun(jobName, acquisition.runId().toString(), Instant.ofEpochMilli(start));
        }
        try {
            invoke(ctx);
            outcome = checkLockAfterRun(acquisition);
            if (outcome == RunOutcome.SUCCESS) {
                elapsed = System.currentTimeMillis() - start;
                onSuccess(elapsed);
            }
        } catch (LockStolenException e) {
            outcome = RunOutcome.STOLEN;
            log.warn("[Vigil] Job '{}' stopped - lock stolen (token={})", jobName, acquisition.fencingToken());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = RunOutcome.STOLEN;
            log.warn("[Vigil] Job '{}' stopped - lock stolen (token={})", jobName, acquisition.fencingToken());
        } catch (RuntimeException e) {
            errorMsg = e.getMessage();
            log.error("[Vigil] Job '{}' failed", jobName, e);
        } catch (IllegalAccessException e) {
            errorMsg = e.getMessage();
            log.error("[Vigil] Job '{}' failed - method not accessible", jobName, e);
        } finally {
            if (elapsed == 0L) elapsed = System.currentTimeMillis() - start;
            teardown(acquisition, ctx, outcome, errorMsg, elapsed);
        }
    }

    private void invoke(JobContext ctx) throws InterruptedException, IllegalAccessException {
        try {
            if (programmaticAction != null) {
                programmaticAction.accept(ctx);
            } else if (ctx != null) {
                method.invoke(bean, ctx);
            } else {
                method.invoke(bean);
            }
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof LockStolenException lse)  throw lse;
            if (cause instanceof InterruptedException ie)   throw ie;
            if (cause instanceof RuntimeException re)       throw re;
            throw new RuntimeException(cause);
        }
    }

    private RunOutcome checkLockAfterRun(LockAcquisition acquisition) {
        if (Thread.interrupted() || !deps.fencedLock().tryRenew(jobName, acquisition.fencingToken())) {
            log.warn("[Vigil] Job '{}' finished but lock was stolen during execution (token={})",
                    jobName, acquisition.fencingToken());
            return RunOutcome.STOLEN;
        }
        return RunOutcome.SUCCESS;
    }

    private void onSuccess(long elapsed) {
        log.info("[Vigil] Job '{}' completed in {}ms", jobName, elapsed);
        if (deps.advisor() != null) deps.advisor().onRunCompleted(jobName, elapsed, lockTtlSeconds);
    }

    private void teardown(LockAcquisition acquisition, JobContext ctx,
                          RunOutcome outcome, String errorMsg, long elapsed) {
        deps.heartbeatDaemon().deregister(jobName);
        if (ctx != null) JobContext.unbind();
        if (deps.recorder() != null) {
            deps.recorder().finishRun(jobName, acquisition.runId().toString(), Instant.now(),
                    outcome.name(), ctx != null ? ctx.getItemsProcessed() : 0L, errorMsg);
        }
        if (deps.listener() != null) {
            deps.listener().onJobCompleted(jobName, outcome.name().toLowerCase(), elapsed);
            if (outcome == RunOutcome.STOLEN) deps.listener().onLockStolen(jobName);
        }
        if (outcome == RunOutcome.SUCCESS && ctx != null && deps.checkpointManager() != null) {
            clearCheckpoints(acquisition);
        }
        deps.fencedLock().release(jobName, acquisition.fencingToken());
    }

    private void clearCheckpoints(LockAcquisition acquisition) {
        try {
            deps.checkpointManager().clearRun(jobName, acquisition.runId(), acquisition.fencingToken());
        } catch (RuntimeException e) {
            log.warn("[Vigil] Job '{}' completed but checkpoint cleanup failed (token={})",
                    jobName, acquisition.fencingToken(), e);
        }
    }

    private boolean hasJobContext() {
        return programmaticAction != null
                || (method != null && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == JobContext.class);
    }

    private JobContext bindContext(LockAcquisition acquisition) {
        var ctx = new JobContext(jobName, acquisition.runId(),
                acquisition.fencingToken(), deps.checkpointManager(), deps.jackson(),
                deps.listener(), deps.fencedLock());
        JobContext.bind(ctx);
        return ctx;
    }
}
