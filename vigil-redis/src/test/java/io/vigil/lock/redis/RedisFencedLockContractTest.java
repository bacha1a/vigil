package io.vigil.lock.redis;

import io.vigil.core.spi.FencedLock;
import io.vigil.testkit.FencedLockContract;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisFencedLockContractTest extends FencedLockContract {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static FencedLock lock;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        lock = new RedisLock(redis);
    }

    @Override
    protected FencedLock lock() {
        return lock;
    }
}
