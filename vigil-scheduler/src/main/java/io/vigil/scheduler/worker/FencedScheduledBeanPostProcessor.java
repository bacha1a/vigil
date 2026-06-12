package io.vigil.scheduler.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.exception.VigilConfigurationException;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.core.support.StageKeys;
import io.vigil.scheduler.ExactlyOnce;
import io.vigil.scheduler.FencedScheduled;
import io.vigil.scheduler.JobContext;
import io.vigil.scheduler.VigilJobDefinition;
import io.vigil.scheduler.VigilJobLifecycleListener;
import io.vigil.scheduler.advisor.VigilTtlAdvisor;
import io.vigil.scheduler.heartbeat.HeartbeatDaemon;
import io.vigil.scheduler.history.VigilJobRunRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FencedScheduledBeanPostProcessor
        implements BeanPostProcessor, SmartInitializingSingleton, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(FencedScheduledBeanPostProcessor.class);

    private final FencedLock              fencedLock;
    private final CheckpointManager       checkpointManager;
    private final HeartbeatDaemon         heartbeatDaemon;
    private final String                  podId;
    private final ObjectMapper            jackson;
    private final FencedJobWorkerRegistry registry;
    private final Environment             environment;
    private final VigilTtlAdvisor         ttlAdvisor;

    private ApplicationContext applicationContext;

    private final List<PendingDiscovery> pendingDiscoveries = new ArrayList<>();
    private final List<JobRegistration>  registrations      = new ArrayList<>();

    private record PendingDiscovery(Object bean, Method method,
                                    FencedScheduled config, Class<?> beanClass) {}

    private record JobRegistration(String jobName, FencedJobWorker worker,
                                   FencedScheduled config, Class<?> beanClass, Method method,
                                   VigilJobDefinition defn) {
        String cron()           { return config != null ? config.cron()           : defn.cron(); }
        long   fixedRateMs()    { return config != null ? config.fixedRateMs()    : defn.fixedRateMs(); }
        String zone()           { return config != null ? config.zone()           : defn.zone(); }
        long   lockTtlSeconds() { return config != null ? config.lockTtlSeconds() : defn.lockTtlSeconds(); }
    }

    public FencedScheduledBeanPostProcessor(FencedLock fencedLock,
                                            @Nullable CheckpointManager checkpointManager,
                                            HeartbeatDaemon heartbeatDaemon,
                                            String podId,
                                            ObjectMapper jackson,
                                            FencedJobWorkerRegistry registry,
                                            Environment environment,
                                            @Nullable VigilTtlAdvisor ttlAdvisor) {
        this.fencedLock        = fencedLock;
        this.checkpointManager = checkpointManager;
        this.heartbeatDaemon   = heartbeatDaemon;
        this.podId             = podId;
        this.jackson           = jackson;
        this.registry          = registry;
        this.environment       = environment;
        this.ttlAdvisor        = ttlAdvisor;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
        var beanClass = AopUtils.getTargetClass(bean);
        for (var m : beanClass.getDeclaredMethods()) {
            var ann = m.getAnnotation(FencedScheduled.class);
            if (ann == null) continue;
            pendingDiscoveries.add(new PendingDiscovery(bean, m, ann, beanClass));
        }
        return bean;
    }

    private void buildWorkers() {
        var recorder = applicationContext.getBeanProvider(VigilJobRunRecorder.class).getIfAvailable();
        if (recorder == null) {
            var jdbc = applicationContext.getBeanProvider(JdbcTemplate.class).getIfAvailable();
            if (jdbc != null) recorder = new VigilJobRunRecorder(jdbc);
        }
        var lifecycleListener = applicationContext.getBeanProvider(VigilJobLifecycleListener.class).getIfAvailable();
        var deps = new WorkerDeps(fencedLock, checkpointManager, heartbeatDaemon, podId, jackson,
                ttlAdvisor, recorder, lifecycleListener);
        for (var pd : pendingDiscoveries) {
            var worker = FencedJobWorker.forAnnotatedMethod(pd.config(), pd.bean(), pd.method(), deps);
            registry.register(pd.config().name(), worker);
            registry.registerCron(pd.config().name(), pd.config().cron(), pd.config().zone());
            registrations.add(new JobRegistration(pd.config().name(), worker, pd.config(), pd.beanClass(), pd.method(), null));
        }
        for (var defn : applicationContext.getBeansOfType(VigilJobDefinition.class).values()) {
            var worker = FencedJobWorker.forDefinition(defn, deps);
            registry.register(defn.name(), worker);
            registry.registerCron(defn.name(), defn.cron(), defn.zone());
            registrations.add(new JobRegistration(defn.name(), worker, null, null, null, defn));
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        buildWorkers();
        validateNoDuplicateJobNames();
        validateCheckpointStageEnums();
        warnOnTransactional();
        warnOnShortTtl();
        informVirtualThreads();
        warnOnStaleStageNames();
        validateExactlyOnceMethods();
        ensureSeedRows();
        startWorkerThreads();
    }

    private void validateNoDuplicateJobNames() {
        Set<String> seen = new HashSet<>();
        for (var reg : registrations) {
            if (!seen.add(reg.jobName())) {
                throw new VigilConfigurationException(
                        "Duplicate @FencedScheduled job name: '" + reg.jobName() + "'");
            }
        }
    }

    private void validateCheckpointStageEnums() {
        for (var reg : registrations) {
            if (reg.method() == null || !hasJobContextParam(reg.method())) continue;
            if (Arrays.stream(reg.beanClass().getDeclaredClasses()).noneMatch(Class::isEnum)) {
                throw new VigilConfigurationException(
                        "Job '" + reg.jobName() + "' accepts JobContext but "
                        + reg.beanClass().getSimpleName()
                        + " declares no nested enum for stage constants.");
            }
        }
    }

    private static boolean hasJobContextParam(Method m) {
        return m != null && m.getParameterCount() == 1 && m.getParameterTypes()[0] == JobContext.class;
    }

    private void warnOnTransactional() {
        for (var reg : registrations) {
            if (reg.method() == null) continue;
            boolean hasTransactional = Arrays.stream(reg.method().getAnnotations())
                    .anyMatch(a -> a.annotationType().getName().endsWith(".transaction.annotation.Transactional")
                            || a.annotationType().getName().endsWith(".transaction.Transactional"));
            if (hasTransactional) {
                log.error("[Vigil] @FencedScheduled method '{}.{}' is also annotated with @Transactional. "
                        + "Remove @Transactional - Vigil manages transaction boundaries internally.",
                        reg.beanClass().getSimpleName(), reg.method().getName());
            }
        }
    }

    private void warnOnShortTtl() {
        for (var reg : registrations) {
            if (reg.lockTtlSeconds() < 60) {
                log.warn("[Vigil] Job '{}' has lockTtlSeconds={} (< 60). "
                        + "This risks premature lock expiry under GC pauses.",
                        reg.jobName(), reg.lockTtlSeconds());
            }
        }
    }

    private void informVirtualThreads() {
        if (Boolean.parseBoolean(environment.getProperty("spring.threads.virtual.enabled", "false"))) {
            log.info("[Vigil] spring.threads.virtual.enabled=true detected. "
                    + "Vigil's internal threads (heartbeat, orphan detector, job workers) "
                    + "are pinned to platform threads. Your application beans may use virtual threads normally.");
        }
    }

    private void warnOnStaleStageNames() {
        if (checkpointManager == null) return;
        for (var reg : registrations) {
            if (reg.method() == null || !hasJobContextParam(reg.method())) continue;
            var codeStageNames = codeStageNamesFor(reg.beanClass());
            checkpointManager.listStageNames(reg.jobName()).stream()
                    .filter(dbName -> !codeStageNames.contains(dbName))
                    .forEach(dbName -> log.warn(
                            "[Vigil] Job '{}' has DB checkpoint stage '{}' not found in code. "
                            + "This may be a stale stage from a previous version. "
                            + "Clear it via the admin endpoint.",
                            reg.jobName(), dbName));
        }
    }

    private Set<String> codeStageNamesFor(Class<?> beanClass) {
        return Arrays.stream(beanClass.getDeclaredClasses())
                .filter(Class::isEnum)
                .flatMap(e -> Arrays.stream(e.getEnumConstants()))
                .map(c -> StageKeys.toDbKey((Enum<?>) c))
                .collect(Collectors.toSet());
    }

    private void validateExactlyOnceMethods() {
        for (var beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (BeansException e) {
                continue;
            }
            var targetClass = AopUtils.getTargetClass(bean);
            for (var m : targetClass.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(ExactlyOnce.class)) continue;
                if (m.getReturnType() != void.class) {
                    throw new VigilConfigurationException(
                            "@ExactlyOnce method '" + targetClass.getSimpleName() + "."
                            + m.getName() + "' must return void.");
                }
            }
        }
    }

    private void ensureSeedRows() {
        registrations.forEach(reg -> fencedLock.ensureSeedRow(reg.jobName()));
    }

    private void startWorkerThreads() {
        for (var reg : registrations) {
            if (!reg.cron().isBlank()) {
                startCronThread(reg);
            } else if (reg.fixedRateMs() > 0) {
                startFixedRateThread(reg);
            } else {
                log.warn("[Vigil] Job '{}' has neither cron nor fixedRateMs configured - it will never run.",
                        reg.jobName());
            }
        }
    }

    private void startCronThread(JobRegistration reg) {
        var expression = CronExpression.parse(reg.cron());
        var zone       = ZoneId.of(reg.zone());
        startDaemonThread("vigil-job-worker-" + reg.jobName(), () -> {
            while (!Thread.currentThread().isInterrupted()) {
                var next = expression.next(ZonedDateTime.now(zone));
                if (next == null) break;
                var delayMs = Duration.between(ZonedDateTime.now(zone), next).toMillis();
                if (delayMs > 0 && sleepUninterrupted(delayMs)) break;
                if (registry.isSuspended(reg.jobName())) continue;
                runTickSafely(reg.jobName(), reg.worker()::run);
            }
        });
    }

    private void startFixedRateThread(JobRegistration reg) {
        var rateMs = reg.fixedRateMs();
        startDaemonThread("vigil-job-worker-" + reg.jobName(), () -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (!registry.isSuspended(reg.jobName())) runTickSafely(reg.jobName(), reg.worker()::run);
                if (sleepUninterrupted(rateMs)) break;
            }
        });
    }

    static void runTickSafely(String jobName, Runnable tick) {
        try {
            tick.run();
        } catch (RuntimeException e) {
            log.warn("[Vigil] Job '{}' tick threw - will retry on next schedule", jobName, e);
        } finally {
            Thread.interrupted();
        }
    }

    private static void startDaemonThread(String name, Runnable body) {
        Thread.ofPlatform().daemon(true).name(name).start(body);
    }

    private static boolean sleepUninterrupted(long ms) {
        try {
            Thread.sleep(ms);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
