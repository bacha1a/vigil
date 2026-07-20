package io.vigil.actuator;

import io.micrometer.core.instrument.MeterRegistry;
import io.vigil.actuator.endpoint.VigilJobsEndpoint;
import io.vigil.actuator.health.VigilLockHealthIndicator;
import io.vigil.actuator.metrics.VigilMetrics;
import io.vigil.core.spi.FencedLock;
import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration(
        after = {JdbcTemplateAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class},
        afterName = "io.vigil.autoconfigure.VigilAutoConfiguration")
public class VigilActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public VigilJobsEndpoint vigilJobsEndpoint(JdbcTemplate jdbc, FencedJobWorkerRegistry registry,
                                               org.springframework.beans.factory.ObjectProvider<MeterRegistry> meterProvider) {
        return new VigilJobsEndpoint(jdbc, registry, meterProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public VigilMetrics vigilMetrics(MeterRegistry registry) {
        return new VigilMetrics(registry);
    }

    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "vigilLockHealthIndicator")
    @ConditionalOnBean(FencedLock.class)
    public VigilLockHealthIndicator vigilLockHealthIndicator(FencedLock lock) {
        return new VigilLockHealthIndicator(lock);
    }
}
