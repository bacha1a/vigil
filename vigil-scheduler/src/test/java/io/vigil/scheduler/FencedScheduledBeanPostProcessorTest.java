package io.vigil.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.exception.VigilConfigurationException;
import io.vigil.scheduler.heartbeat.HeartbeatDaemon;
import io.vigil.scheduler.history.VigilJobRunRecorder;
import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import io.vigil.scheduler.worker.FencedScheduledBeanPostProcessor;
import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FencedScheduledBeanPostProcessorTest {

    @Mock FencedLock              fencedLock;
    @Mock CheckpointManager       checkpointManager;
    @Mock HeartbeatDaemon         heartbeatDaemon;
    @Mock FencedJobWorkerRegistry registry;
    @Mock Environment             environment;
    @Mock ApplicationContext      applicationContext;

    FencedScheduledBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FencedScheduledBeanPostProcessor(
                fencedLock, checkpointManager, heartbeatDaemon, "pod-1",
                new ObjectMapper(), registry, environment, null);
        processor.setApplicationContext(applicationContext);
        when(environment.getProperty("spring.threads.virtual.enabled", "false")).thenReturn("false");
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[0]);
        when(fencedLock.tryAcquire(any(), any(), any()))
                .thenReturn(Optional.of(new LockAcquisition(1L, UUID.randomUUID())));
        @SuppressWarnings("unchecked")
        ObjectProvider<VigilJobRunRecorder> recorderProvider = mock(ObjectProvider.class);
        when(applicationContext.getBeanProvider(VigilJobRunRecorder.class)).thenReturn(recorderProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(applicationContext.getBeanProvider(JdbcTemplate.class)).thenReturn(jdbcProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<VigilJobLifecycleListener> listenerProvider = mock(ObjectProvider.class);
        when(applicationContext.getBeanProvider(VigilJobLifecycleListener.class)).thenReturn(listenerProvider);
    }

    static class JobA {
        @FencedScheduled(name = "job-a", fixedRateMs = Long.MAX_VALUE)
        public void run() {}
    }

    static class JobADuplicate {
        @FencedScheduled(name = "job-a", fixedRateMs = Long.MAX_VALUE)
        public void run() {}
    }

    static class CheckpointJobNoEnum {
        @FencedScheduled(name = "cp-job", fixedRateMs = Long.MAX_VALUE)
        public void run(JobContext ctx) {}
    }

    static class CheckpointJobWithEnum {
        enum Stage { STEP1, STEP2 }

        @FencedScheduled(name = "cp-job", fixedRateMs = Long.MAX_VALUE)
        public void run(JobContext ctx) {}
    }

    static class BeanWithNonVoidExactlyOnce {
        @ExactlyOnce
        public String charge() { return "done"; }
    }

    static class BeanWithVoidExactlyOnce {
        @ExactlyOnce
        public void charge() {}
    }

    @Nested
    @DisplayName("duplicate job names")
    class DuplicateJobNames {

        @Test
        @DisplayName("throws VigilConfigurationException when two beans share a job name")
        void throwsOnDuplicate() {
            processor.postProcessAfterInitialization(new JobA(), "jobA");
            processor.postProcessAfterInitialization(new JobADuplicate(), "jobADuplicate");

            assertThatThrownBy(() -> processor.afterSingletonsInstantiated())
                    .isInstanceOf(VigilConfigurationException.class)
                    .hasMessageContaining("job-a");
        }

        @Test
        @DisplayName("passes when all job names are unique")
        void passesWhenUnique() {
            processor.postProcessAfterInitialization(new JobA(), "jobA");

            assertThatNoException().isThrownBy(() -> processor.afterSingletonsInstantiated());
        }
    }

    @Nested
    @DisplayName("checkpoint stage enum validation")
    class CheckpointStageEnum {

        @Test
        @DisplayName("throws when method takes JobContext but no nested enum")
        void throwsWhenNoNestedEnum() {
            processor.postProcessAfterInitialization(new CheckpointJobNoEnum(), "bean");

            assertThatThrownBy(() -> processor.afterSingletonsInstantiated())
                    .isInstanceOf(VigilConfigurationException.class)
                    .hasMessageContaining("nested enum");
        }

        @Test
        @DisplayName("passes when checkpoint=true and nested enum is present")
        void passesWhenNestedEnumPresent() {
            when(checkpointManager.listStageNames("cp-job")).thenReturn(List.of());
            processor.postProcessAfterInitialization(new CheckpointJobWithEnum(), "bean");

            assertThatNoException().isThrownBy(() -> processor.afterSingletonsInstantiated());
        }
    }

    @Nested
    @DisplayName("virtual threads handling")
    class VirtualThreads {

        @Test
        @DisplayName("does not throw when virtual threads are enabled - Vigil pins its own threads")
        void doesNotThrowWhenEnabled() {
            when(environment.getProperty("spring.threads.virtual.enabled", "false")).thenReturn("true");

            assertThatNoException().isThrownBy(() -> processor.afterSingletonsInstantiated());
        }

        @Test
        @DisplayName("passes when virtual threads disabled")
        void passesWhenDisabled() {
            processor.postProcessAfterInitialization(new JobA(), "jobA");

            assertThatNoException().isThrownBy(() -> processor.afterSingletonsInstantiated());
        }
    }

    @Nested
    @DisplayName("@ExactlyOnce return type validation")
    class ExactlyOnceValidation {

        @Test
        @DisplayName("throws when @ExactlyOnce method returns non-void")
        void throwsWhenNonVoidReturn() {
            BeanWithNonVoidExactlyOnce bean = new BeanWithNonVoidExactlyOnce();
            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"bean"});
            when(applicationContext.getBean("bean")).thenReturn(bean);

            assertThatThrownBy(() -> processor.afterSingletonsInstantiated())
                    .isInstanceOf(VigilConfigurationException.class)
                    .hasMessageContaining("must return void");
        }

        @Test
        @DisplayName("passes when @ExactlyOnce method returns void")
        void passesWhenVoidReturn() {
            BeanWithVoidExactlyOnce bean = new BeanWithVoidExactlyOnce();
            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"bean"});
            when(applicationContext.getBean("bean")).thenReturn(bean);

            assertThatNoException().isThrownBy(() -> processor.afterSingletonsInstantiated());
        }
    }

    @Nested
    @DisplayName("stale stage name detection")
    class StaleStageNames {

        @Test
        @DisplayName("does not warn when DB stage names match code enum constants")
        void noWarnWhenStageNamesMatch() {
            when(checkpointManager.listStageNames("cp-job"))
                    .thenReturn(List.of("stage.step1", "stage.step2"));
            processor.postProcessAfterInitialization(new CheckpointJobWithEnum(), "bean");

            assertThatNoException().isThrownBy(() -> processor.afterSingletonsInstantiated());
        }

        @Test
        @DisplayName("passes silently when checkpointManager is null")
        void passesWhenCheckpointManagerNull() {
            FencedScheduledBeanPostProcessor p = new FencedScheduledBeanPostProcessor(
                    fencedLock, null, heartbeatDaemon, "pod-1",
                    new ObjectMapper(), registry, environment, null);
            p.setApplicationContext(applicationContext);
            p.postProcessAfterInitialization(new JobA(), "jobA");

            assertThatNoException().isThrownBy(p::afterSingletonsInstantiated);
        }
    }
}
