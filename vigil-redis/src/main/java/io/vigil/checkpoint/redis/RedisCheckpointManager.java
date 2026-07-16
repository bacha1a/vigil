package io.vigil.checkpoint.redis;

import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.spi.CheckpointManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class RedisCheckpointManager implements CheckpointManager {

    private static final String LOCK_PREFIX      = "vigil:lock:";
    private static final String ENTRY_PREFIX     = "vigil:ckpt:";
    private static final String RUN_INDEX_PREFIX = "vigil:ckpt-run:";
    private static final String JOB_INDEX_PREFIX = "vigil:ckpt-job:";

    private final StringRedisTemplate      redis;
    private final DefaultRedisScript<Long> saveScript;
    private final DefaultRedisScript<Long> clearRunScript;

    public RedisCheckpointManager(StringRedisTemplate redis) {
        this.redis          = redis;
        this.saveScript     = new DefaultRedisScript<>(loadScript("save.lua"), Long.class);
        this.clearRunScript = new DefaultRedisScript<>(loadScript("clear-run.lua"), Long.class);
    }

    @Override
    public void save(CheckpointEntry entry) {
        boolean storedValueNull = entry.storedValue() == null;
        Long result = redis.execute(
                saveScript,
                List.of(
                        lockKey(entry.jobName()),
                        entryKey(entry.jobName(), entry.runId(), entry.stageName()),
                        runIndexKey(entry.jobName(), entry.runId()),
                        jobIndexKey(entry.jobName())),
                String.valueOf(entry.fencingToken()),
                entry.status().name(),
                storedValueNull ? "" : entry.storedValue(),
                entry.valueType() == null ? "" : entry.valueType(),
                String.valueOf(Instant.now().toEpochMilli()),
                entry.jobName(),
                entry.runId().toString(),
                entry.stageName(),
                storedValueNull ? "1" : "0");

        if (Long.valueOf(0L).equals(result)) {
            throw new LockStolenException(
                    "Fencing token " + entry.fencingToken() + " is stale for job " + entry.jobName());
        }
    }

    @Override
    public void clearRun(String jobName, UUID runId, long fencingToken) {
        redis.execute(
                clearRunScript,
                List.of(lockKey(jobName), runIndexKey(jobName, runId)),
                String.valueOf(fencingToken),
                jobName,
                runId.toString());
    }

    @Override
    public Optional<CheckpointEntry> load(String jobName, UUID runId, String stageName) {
        Map<Object, Object> hash = redis.opsForHash().entries(entryKey(jobName, runId, stageName));
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromHash(hash));
    }

    @Override
    public boolean isComplete(String jobName, UUID runId, String stageName) {
        Object status = redis.opsForHash().get(entryKey(jobName, runId, stageName), "status");
        return CheckpointStatus.COMPLETE.name().equals(status);
    }

    @Override
    public List<String> listStageNames(String jobName) {
        Set<String> members = redis.opsForSet().members(jobIndexKey(jobName));
        return members == null ? List.of() : new ArrayList<>(members);
    }

    @Override
    public boolean hasAnyCheckpoint(String jobName, UUID runId) {
        Long size = redis.opsForSet().size(runIndexKey(jobName, runId));
        return size != null && size > 0L;
    }

    private static CheckpointEntry fromHash(Map<Object, Object> hash) {
        boolean storedValueNull = "1".equals(hash.get("sv_null"));
        return new CheckpointEntry(
                (String) hash.get("job_name"),
                UUID.fromString((String) hash.get("run_id")),
                (String) hash.get("stage_name"),
                CheckpointStatus.valueOf((String) hash.get("status")),
                storedValueNull ? null : (String) hash.get("stored_value"),
                (String) hash.get("value_type"),
                Long.parseLong((String) hash.get("fencing_token")));
    }

    private static String lockKey(String jobName) {
        return LOCK_PREFIX + jobName;
    }

    private static String entryKey(String jobName, UUID runId, String stageName) {
        return ENTRY_PREFIX + jobName + ":" + runId + ":" + stageName;
    }

    private static String runIndexKey(String jobName, UUID runId) {
        return RUN_INDEX_PREFIX + jobName + ":" + runId;
    }

    private static String jobIndexKey(String jobName) {
        return JOB_INDEX_PREFIX + jobName;
    }

    private static String loadScript(String name) {
        String path = "/io/vigil/checkpoint/redis/" + name;
        try (InputStream is = RedisCheckpointManager.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Lua script not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Lua script: " + name, e);
        }
    }
}
