package io.vigil.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.checkpoint.dynamodb.DynamoCheckpointManager;
import io.vigil.checkpoint.jdbc.JdbcCheckpointManager;
import io.vigil.checkpoint.mongo.MongoCheckpointManager;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.lock.dynamodb.DynamoFencedLock;
import io.vigil.lock.jdbc.JdbcFencedLock;
import io.vigil.lock.mongo.MongoFencedLock;
import io.vigil.lock.redis.RedisLock;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import io.vigil.autoconfigure.http.VigilRestTemplateCustomizer;
import io.vigil.autoconfigure.schema.VigilSchemaInitializer;
import io.vigil.scheduler.advisor.ExactlyOnceStartupValidator;
import io.vigil.scheduler.advisor.VigilTtlAdvisor;
import io.vigil.scheduler.aspect.ExactlyOnceAspect;
import io.vigil.scheduler.aspect.RequiresJobContextAspect;
import io.vigil.scheduler.heartbeat.HeartbeatDaemon;
import io.vigil.scheduler.history.VigilJobRunRecorder;
import io.vigil.scheduler.history.VigilRunHistoryRetentionTask;
import io.vigil.scheduler.orphan.OrphanDetector;
import io.vigil.scheduler.worker.FencedJobWorkerRegistry;
import io.vigil.scheduler.worker.FencedScheduledBeanPostProcessor;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;

import java.util.UUID;

@AutoConfiguration
@EnableConfigurationProperties(VigilProperties.class)
@ConditionalOnProperty(name = "spring.vigil.enabled", matchIfMissing = true)
public class VigilAutoConfiguration {

    @Bean("vigilPodId")
    public String podId(VigilProperties props) {
        return props.getPodId() != null ? props.getPodId() : UUID.randomUUID().toString();
    }

    @Bean
    public HeartbeatDaemon heartbeatDaemon(FencedLock fencedLock) {
        return new HeartbeatDaemon(fencedLock);
    }

    @Bean
    public FencedJobWorkerRegistry fencedJobWorkerRegistry() {
        return new FencedJobWorkerRegistry();
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public OrphanDetector orphanDetector(JdbcTemplate jdbc, FencedJobWorkerRegistry registry,
                                         VigilProperties props) {
        return new OrphanDetector(jdbc, registry, props.getOrphanScanIntervalMs());
    }

    @Bean
    public ExactlyOnceAspect exactlyOnceAspect() {
        return new ExactlyOnceAspect();
    }

    @Bean
    @ConditionalOnMissingBean(RequiresJobContextAspect.class)
    public RequiresJobContextAspect requiresJobContextAspect() {
        return new RequiresJobContextAspect();
    }

    @Bean
    public ExactlyOnceStartupValidator exactlyOnceStartupValidator(
            ApplicationContext context, Environment environment) {
        return new ExactlyOnceStartupValidator(context, environment);
    }

    @Bean
    public VigilTtlAdvisor vigilTtlAdvisor(FencedJobWorkerRegistry registry) {
        return new VigilTtlAdvisor(registry);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public VigilJobRunRecorder vigilJobRunRecorder(JdbcTemplate jdbc) {
        return new VigilJobRunRecorder(jdbc);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public VigilRunHistoryRetentionTask vigilRunHistoryRetentionTask(JdbcTemplate jdbc, VigilProperties props) {
        return new VigilRunHistoryRetentionTask(jdbc, props.getRunHistoryRetention(),
                props.getRunHistoryCleanupIntervalMs());
    }

    @Bean
    @ConditionalOnClass(RestTemplate.class)
    public VigilRestTemplateCustomizer vigilRestTemplateCustomizer() {
        return new VigilRestTemplateCustomizer();
    }

    @Bean
    public static FencedScheduledBeanPostProcessor fencedScheduledBeanPostProcessor(
            FencedLock fencedLock,
            @Nullable CheckpointManager checkpointManager,
            HeartbeatDaemon heartbeatDaemon,
            @Qualifier("vigilPodId") String podId,
            ObjectMapper jackson,
            FencedJobWorkerRegistry registry,
            Environment environment,
            VigilTtlAdvisor ttlAdvisor) {
        return new FencedScheduledBeanPostProcessor(
                fencedLock, checkpointManager, heartbeatDaemon, podId, jackson, registry, environment,
                ttlAdvisor);
    }

    @Bean
    @ConditionalOnMissingClass("org.flywaydb.core.Flyway")
    @ConditionalOnClass(JdbcTemplate.class)
    public VigilSchemaInitializer vigilSchemaInitializer(JdbcTemplate jdbc) {
        return new VigilSchemaInitializer(jdbc);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.flywaydb.core.Flyway")
    static class FlywaySchemaConfiguration {

        private static final String VIGIL_LOCATION = "classpath:io/vigil/db/migration/{vendor}";

        @Bean
        public FlywayConfigurationCustomizer vigilFlywayCustomizer() {
            return config -> {
                var locations = new ArrayList<>(Arrays.asList(config.getLocations()));
                var vigilLocation = new Location(VIGIL_LOCATION);
                if (!locations.contains(vigilLocation)) {
                    locations.add(vigilLocation);
                    config.locations(locations.toArray(new Location[0]));
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.lock.jdbc.JdbcFencedLock")
    static class JdbcLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(FencedLock.class)
        public JdbcFencedLock jdbcFencedLock(JdbcTemplate jdbc, TransactionTemplate tx) {
            return new JdbcFencedLock(jdbc, tx);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.lock.redis.RedisLock")
    static class RedisLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(FencedLock.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        public RedisLock redisLock(StringRedisTemplate redis) {
            return new RedisLock(redis);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.checkpoint.jdbc.JdbcCheckpointManager")
    static class JdbcCheckpointConfiguration {

        @Bean
        @ConditionalOnMissingBean(CheckpointManager.class)
        public JdbcCheckpointManager jdbcCheckpointManager(JdbcTemplate jdbc, TransactionTemplate tx) {
            return new JdbcCheckpointManager(jdbc, tx);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.lock.mongo.MongoFencedLock")
    static class MongoLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(FencedLock.class)
        @ConditionalOnBean(MongoDatabaseFactory.class)
        public MongoFencedLock mongoFencedLock(MongoDatabaseFactory factory) {
            return new MongoFencedLock(factory.getMongoDatabase());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.checkpoint.mongo.MongoCheckpointManager")
    static class MongoCheckpointConfiguration {

        @Bean
        @ConditionalOnMissingBean(CheckpointManager.class)
        @ConditionalOnBean(MongoDatabaseFactory.class)
        public MongoCheckpointManager mongoCheckpointManager(MongoDatabaseFactory factory) {
            return new MongoCheckpointManager(factory.getMongoDatabase());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.lock.dynamodb.DynamoFencedLock")
    static class DynamoLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(FencedLock.class)
        @ConditionalOnBean(DynamoDbClient.class)
        public DynamoFencedLock dynamoFencedLock(DynamoDbClient ddb) {
            return new DynamoFencedLock(ddb);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.vigil.checkpoint.dynamodb.DynamoCheckpointManager")
    static class DynamoCheckpointConfiguration {

        @Bean
        @ConditionalOnMissingBean(CheckpointManager.class)
        @ConditionalOnBean(DynamoDbClient.class)
        public DynamoCheckpointManager dynamoCheckpointManager(DynamoDbClient ddb) {
            return new DynamoCheckpointManager(ddb);
        }
    }
}
