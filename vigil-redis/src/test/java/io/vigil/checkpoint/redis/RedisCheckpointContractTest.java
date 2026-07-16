package io.vigil.checkpoint.redis;

import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.lock.redis.RedisLock;
import io.vigil.testkit.CheckpointManagerContract;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisCheckpointContractTest extends CheckpointManagerContract {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static FencedLock        lock;
    static CheckpointManager checkpoints;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        lock = new RedisLock(redis);
        checkpoints = new RedisCheckpointManager(redis);
    }

    @Override
    protected CheckpointManager checkpoints() {
        return checkpoints;
    }

    @Override
    protected FencedLock lock() {
        return lock;
    }
}
