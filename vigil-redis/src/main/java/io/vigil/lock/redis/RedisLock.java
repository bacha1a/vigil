package io.vigil.lock.redis;

import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.FencedLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RedisLock implements FencedLock {

    private static final String LOCK_PREFIX = "vigil:lock:";

    private final StringRedisTemplate              redis;
    private final DefaultRedisScript<List<Object>> acquireScript;
    private final DefaultRedisScript<Long>         heartbeatScript;
    private final DefaultRedisScript<Long>         releaseScript;

    @SuppressWarnings("unchecked")
    public RedisLock(StringRedisTemplate redis) {
        this.redis           = redis;
        this.acquireScript   = new DefaultRedisScript<>(loadScript("acquire.lua"),   (Class<List<Object>>) (Class<?>) List.class);
        this.heartbeatScript = new DefaultRedisScript<>(loadScript("heartbeat.lua"), Long.class);
        this.releaseScript   = new DefaultRedisScript<>(loadScript("release.lua"),   Long.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<LockAcquisition> tryAcquire(String jobName, String podId, Duration ttl) {
        String runId = UUID.randomUUID().toString();

        List<Object> result = redis.execute(
                acquireScript,
                List.of(lockKey(jobName)),
                podId,
                String.valueOf(ttl.getSeconds()),
                runId);

        if (result == null || !Long.valueOf(1L).equals(result.getFirst())) {
            return Optional.empty();
        }

        long token = ((Number) result.get(1)).longValue();
        String grantedRunId = String.valueOf(result.get(2));
        return Optional.of(new LockAcquisition(token, UUID.fromString(grantedRunId)));
    }

    @Override
    public boolean tryRenew(String jobName, long fencingToken, Duration ttl) {
        Long result = redis.execute(
                heartbeatScript,
                List.of(lockKey(jobName)),
                String.valueOf(fencingToken),
                String.valueOf(ttl.getSeconds()));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public void release(String jobName, long fencingToken) {
        redis.execute(
                releaseScript,
                List.of(lockKey(jobName)),
                String.valueOf(fencingToken));
    }

    @Override
    public void checkConnectivity() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<String>)
                connection -> connection.ping());
    }

    private static String lockKey(String jobName) {
        return LOCK_PREFIX + jobName;
    }

    private static String loadScript(String name) {
        String path = "/io/vigil/lock/redis/" + name;
        try (InputStream is = RedisLock.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Lua script not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Lua script: " + name, e);
        }
    }
}
