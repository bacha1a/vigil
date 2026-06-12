package io.vigil.autoconfigure;

import io.vigil.autoconfigure.schema.VigilSchemaInitializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.checkpoint.jdbc.JdbcCheckpointManager;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.lock.jdbc.JdbcFencedLock;
import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import io.vigil.scheduler.heartbeat.HeartbeatDaemon;
import io.vigil.scheduler.orphan.OrphanDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VigilAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VigilAutoConfiguration.class))
            .withUserConfiguration(MockInfrastructure.class);

    @Configuration(proxyBeanMethods = false)
    static class MockInfrastructure {

        @Bean
        JdbcTemplate jdbcTemplate() {
            var mock = mock(JdbcTemplate.class);
            when(mock.queryForList(any(String.class), eq(String.class), any()))
                    .thenReturn(List.of());
            return mock;
        }

        @Bean
        TransactionTemplate transactionTemplate() {
            return mock(TransactionTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Nested
    @DisplayName("when disabled")
    class WhenDisabled {

        @Test
        @DisplayName("no Vigil beans are registered")
        void noBeansRegistered() {
            runner.withPropertyValues("spring.vigil.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(FencedLock.class);
                        assertThat(ctx).doesNotHaveBean(FencedJobWorkerRegistry.class);
                        assertThat(ctx).doesNotHaveBean(HeartbeatDaemon.class);
                        assertThat(ctx).doesNotHaveBean(OrphanDetector.class);
                    });
        }
    }

    @Nested
    @DisplayName("podId")
    class PodIdTests {

        @Test
        @DisplayName("generates a valid UUID when spring.vigil.pod-id is not configured")
        void generatesUuidWhenNotConfigured() {
            runner.run(ctx -> {
                var podId = ctx.getBean("vigilPodId", String.class);
                assertThat(UUID.fromString(podId)).isNotNull();
            });
        }

        @Test
        @DisplayName("uses the configured value when spring.vigil.pod-id is set")
        void usesConfiguredValue() {
            runner.withPropertyValues("spring.vigil.pod-id=my-pod-1")
                    .run(ctx -> {
                        var podId = ctx.getBean("vigilPodId", String.class);
                        assertThat(podId).isEqualTo("my-pod-1");
                    });
        }
    }

    @Nested
    @DisplayName("FencedLock selection")
    class FencedLockSelection {

        @Test
        @DisplayName("creates JdbcFencedLock by default when JDBC is on classpath")
        void createsJdbcLockByDefault() {
            runner.run(ctx -> assertThat(ctx).hasSingleBean(JdbcFencedLock.class));
        }

        @Test
        @DisplayName("does not create JdbcFencedLock when user provides a FencedLock bean")
        void doesNotCreateJdbcLockWhenUserProvidesOne() {
            runner.withBean(FencedLock.class, () -> mock(FencedLock.class))
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(JdbcFencedLock.class);
                        assertThat(ctx).hasSingleBean(FencedLock.class);
                    });
        }
    }

    @Nested
    @DisplayName("CheckpointManager selection")
    class CheckpointManagerSelection {

        @Test
        @DisplayName("creates JdbcCheckpointManager by default when JDBC is on classpath")
        void createsJdbcCheckpointManagerByDefault() {
            runner.run(ctx -> assertThat(ctx).hasSingleBean(JdbcCheckpointManager.class));
        }

        @Test
        @DisplayName("does not create JdbcCheckpointManager when user provides a CheckpointManager bean")
        void doesNotCreateJdbcCheckpointWhenUserProvidesOne() {
            runner.withBean(CheckpointManager.class, () -> mock(CheckpointManager.class))
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(JdbcCheckpointManager.class));
        }
    }

    @Nested
    @DisplayName("schema management")
    class SchemaManagement {

        @Test
        @DisplayName("registers FlywayConfigurationCustomizer when Flyway is on classpath")
        void registersFlywayCustomizerWhenFlywayPresent() {
            runner.run(ctx -> assertThat(ctx).hasSingleBean(
                    org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer.class));
        }

        @Test
        @DisplayName("does not register VigilSchemaInitializer when Flyway is on classpath")
        void doesNotRegisterSchemaInitializerWhenFlywayPresent() {
            runner.run(ctx -> assertThat(ctx).doesNotHaveBean(VigilSchemaInitializer.class));
        }
    }

    @Nested
    @DisplayName("core beans")
    class CoreBeans {

        @Test
        @DisplayName("always registers HeartbeatDaemon, FencedJobWorkerRegistry, and OrphanDetector")
        void alwaysRegistersCoreBeans() {
            runner.run(ctx -> {
                assertThat(ctx).hasSingleBean(HeartbeatDaemon.class);
                assertThat(ctx).hasSingleBean(FencedJobWorkerRegistry.class);
                assertThat(ctx).hasSingleBean(OrphanDetector.class);
            });
        }

        @Test
        @DisplayName("OrphanDetector uses orphanScanIntervalMs from properties")
        void orphanDetectorUsesConfiguredInterval() {
            runner.withPropertyValues("spring.vigil.orphan-scan-interval-ms=5000")
                    .run(ctx -> assertThat(ctx).hasSingleBean(OrphanDetector.class));
        }
    }
}
