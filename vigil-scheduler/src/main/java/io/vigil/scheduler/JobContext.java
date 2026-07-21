package io.vigil.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.exception.CheckpointTypeException;
import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.core.support.CheckpointTypeValidator;
import io.vigil.core.support.StageKeys;
import io.vigil.core.support.TriFunction;
import io.vigil.scheduler.context.ExactlyOnceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class JobContext {

    private static final ThreadLocal<JobContext> CURRENT = new ThreadLocal<>();

    private final String                    jobName;
    private final UUID                      runId;
    private final long                      fencingToken;
    private final CheckpointManager        checkpointManager;
    private final ObjectMapper             jackson;
    private final AtomicLong               itemsProcessed = new AtomicLong(0);
    private final boolean                  resume;
    private final VigilJobLifecycleListener listener;
    private final FencedLock               fencedLock;

    public JobContext(String jobName, UUID runId, long fencingToken,
                      CheckpointManager checkpointManager, ObjectMapper jackson) {
        this(jobName, runId, fencingToken, checkpointManager, jackson, null, null);
    }

    public JobContext(String jobName, UUID runId, long fencingToken,
                      CheckpointManager checkpointManager, ObjectMapper jackson,
                      VigilJobLifecycleListener listener) {
        this(jobName, runId, fencingToken, checkpointManager, jackson, listener, null);
    }

    public JobContext(String jobName, UUID runId, long fencingToken,
                      CheckpointManager checkpointManager, ObjectMapper jackson,
                      VigilJobLifecycleListener listener, FencedLock fencedLock) {
        this.jobName           = jobName;
        this.runId             = runId;
        this.fencingToken      = fencingToken;
        this.checkpointManager = checkpointManager;
        this.jackson           = jackson;
        this.listener          = listener;
        this.fencedLock        = fencedLock;
        this.resume            = checkpointManager.hasAnyCheckpoint(jobName, runId);
        if (resume && listener != null) listener.onFailoverRecovery(jobName);
    }

    public static void bind(JobContext ctx) { CURRENT.set(ctx); }
    public static void unbind()            { CURRENT.remove(); }
    public static boolean isBound()        { return CURRENT.get() != null; }

    public static JobContext current() {
        JobContext ctx = CURRENT.get();
        if (ctx == null) throw new IllegalStateException("No JobContext bound to current thread");
        return ctx;
    }

    public <T> T step(Enum<?> stage, Supplier<T> action) {
        String key = StageKeys.toDbKey(stage);
        if (checkpointManager.isComplete(jobName, runId, key)) {
            return deserialize(checkpointManager.load(jobName, runId, key)
                    .orElseThrow(() -> new IllegalStateException("No checkpoint for complete stage: " + key)));
        }
        T result = action.get();
        CheckpointTypeValidator.validate(result);
        save(key, CheckpointStatus.COMPLETE,
                result == null ? null : writeJson(result),
                result == null ? null : result.getClass().getName());
        return result;
    }

    public void step(Enum<?> stage, Runnable action) {
        String key = StageKeys.toDbKey(stage);
        if (checkpointManager.isComplete(jobName, runId, key)) return;
        action.run();
        save(key, CheckpointStatus.COMPLETE, null, null);
    }

    public <T> void forEach(Enum<?> stage, List<T> items, Function<T, ?> idFn,
                            BiConsumer<T, Long> action) {
        String key = StageKeys.toDbKey(stage);
        if (checkpointManager.isComplete(jobName, runId, key)) return;
        String lastId = loadLastId(key);
        for (T item : items) {
            String itemId = idFn.apply(item).toString();
            if (lastId != null && itemId.compareTo(lastId) <= 0) continue;
            withContext(itemId, () -> {
                action.accept(item, fencingToken);
                saveItemProgress(key, itemId);
                return null;
            });
        }
        markComplete(key);
    }

    public <T> void forEach(Enum<?> stage, List<T> items, Function<T, ?> idFn,
                            Consumer<T> action) {
        forEach(stage, items, idFn, (item, token) -> action.accept(item));
    }

    public <T, S> S forEachWithState(Enum<?> stage, List<T> items, Function<T, ?> idFn,
                                     S initialState, TriFunction<T, Long, S, S> action) {
        String key = StageKeys.toDbKey(stage);
        if (checkpointManager.isComplete(jobName, runId, key)) {
            return deserializeState(checkpointManager.load(jobName, runId, key)
                    .orElseThrow(() -> new IllegalStateException("No checkpoint for complete stage: " + key)));
        }
        CheckpointTypeValidator.validateClass(initialState.getClass());
        LastIdAndState loaded = loadLastIdAndState(key, initialState);
        String lastId = loaded.lastId();
        @SuppressWarnings("unchecked") S state = (S) loaded.state();
        for (T item : items) {
            String itemId = idFn.apply(item).toString();
            if (lastId != null && itemId.compareTo(lastId) <= 0) continue;
            final S previous = state;
            state = withContext(itemId, () -> {
                S next = action.apply(item, fencingToken, previous);
                saveItemWithState(key, itemId, next);
                return next;
            });
        }
        markCompleteWithState(key, state);
        return state;
    }

    public <T, S> S forEachWithState(Enum<?> stage, List<T> items, Function<T, ?> idFn,
                                     S initialState, BiFunction<T, S, S> action) {
        return forEachWithState(stage, items, idFn, initialState, (item, token, state) -> action.apply(item, state));
    }

    public <T> void forEachPage(Enum<?> stage, Function<Optional<String>, List<T>> pageLoader,
                                Function<T, ?> idFn, BiConsumer<T, Long> action) {
        String key = StageKeys.toDbKey(stage);
        if (checkpointManager.isComplete(jobName, runId, key)) return;
        Optional<String> cursor = Optional.ofNullable(loadLastId(key));
        while (true) {
            List<T> page = pageLoader.apply(cursor);
            if (page.isEmpty()) break;
            for (T item : page) {
                String itemId = idFn.apply(item).toString();
                withContext(itemId, () -> {
                    action.accept(item, fencingToken);
                    saveItemProgress(key, itemId);
                    return null;
                });
                cursor = Optional.of(itemId);
            }
        }
        markComplete(key);
    }

    public <T> void forEachPage(Enum<?> stage, Function<Optional<String>, List<T>> pageLoader,
                                Function<T, ?> idFn, Consumer<T> action) {
        forEachPage(stage, pageLoader, idFn, (item, token) -> action.accept(item));
    }

    public <T, S> S forEachPageWithState(Enum<?> stage, Function<Optional<String>, List<T>> pageLoader,
                                         Function<T, ?> idFn, S initialState,
                                         TriFunction<T, Long, S, S> action) {
        String key = StageKeys.toDbKey(stage);
        if (checkpointManager.isComplete(jobName, runId, key)) {
            return deserializeState(checkpointManager.load(jobName, runId, key)
                    .orElseThrow(() -> new IllegalStateException("No checkpoint for complete stage: " + key)));
        }
        CheckpointTypeValidator.validateClass(initialState.getClass());
        LastIdAndState loaded = loadLastIdAndState(key, initialState);
        Optional<String> cursor = Optional.ofNullable(loaded.lastId());
        @SuppressWarnings("unchecked") S state = (S) loaded.state();
        while (true) {
            List<T> page = pageLoader.apply(cursor);
            if (page.isEmpty()) break;
            for (T item : page) {
                String itemId = idFn.apply(item).toString();
                final S previous = state;
                state = withContext(itemId, () -> {
                    S next = action.apply(item, fencingToken, previous);
                    saveItemWithState(key, itemId, next);
                    return next;
                });
                cursor = Optional.of(itemId);
            }
        }
        markCompleteWithState(key, state);
        return state;
    }

    public <T, S> S forEachPageWithState(Enum<?> stage, Function<Optional<String>, List<T>> pageLoader,
                                         Function<T, ?> idFn, S initialState,
                                         BiFunction<T, S, S> action) {
        return forEachPageWithState(stage, pageLoader, idFn, initialState,
                (item, token, state) -> action.apply(item, state));
    }

    public boolean isStageComplete(Enum<?> stage) {
        return checkpointManager.isComplete(jobName, runId, StageKeys.toDbKey(stage));
    }

    public void completeStage(Enum<?> stage) {
        save(StageKeys.toDbKey(stage), CheckpointStatus.COMPLETE, null, null);
    }

    public long getFencingToken()   { return fencingToken; }
    public UUID getRunId()          { return runId; }
    public long getItemsProcessed() { return itemsProcessed.get(); }
    public boolean isResume()       { return resume; }

    public void assertStillHeld() {
        if (fencedLock == null) {
            throw new IllegalStateException("assertStillHeld() requires a lock-backed JobContext");
        }
        if (!fencedLock.tryRenew(jobName, fencingToken)) {
            throw new LockStolenException(
                    "Job '" + jobName + "' no longer holds the lock (token " + fencingToken + ")");
        }
    }

    private <R> R withContext(String itemId, Supplier<R> body) {
        ExactlyOnceContext.bind(runId.toString(), fencingToken, itemId);
        try {
            R result = body.get();
            itemsProcessed.incrementAndGet();
            if (listener != null) listener.onItemProcessed(jobName);
            return result;
        } finally {
            ExactlyOnceContext.unbind();
        }
    }

    private void save(String stageName, CheckpointStatus status, String value, String valueType) {
        checkpointManager.save(new CheckpointEntry(jobName, runId, stageName, status, value, valueType, fencingToken));
        if (listener != null) listener.onCheckpointSaved(jobName);
    }

    private void saveItemProgress(String stageName, String itemId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastId", itemId);
        save(stageName, CheckpointStatus.IN_PROGRESS, writeJson(m), null);
    }

    private void saveItemWithState(String stageName, String itemId, Object state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastId", itemId);
        m.put("state", state);
        m.put("stateType", state.getClass().getName());
        save(stageName, CheckpointStatus.IN_PROGRESS, writeJson(m), null);
    }

    private void markComplete(String stageName) {
        save(stageName, CheckpointStatus.COMPLETE, null, null);
    }

    private void markCompleteWithState(String stageName, Object state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastId", null);
        m.put("state", state);
        m.put("stateType", state.getClass().getName());
        save(stageName, CheckpointStatus.COMPLETE, writeJson(m), null);
    }

    private String loadLastId(String stageName) {
        return checkpointManager.load(jobName, runId, stageName)
                .map(entry -> {
                    if (entry.storedValue() == null) return null;
                    try {
                        JsonNode node = jackson.readTree(entry.storedValue());
                        JsonNode lastIdNode = node.get("lastId");
                        return (lastIdNode == null || lastIdNode.isNull()) ? null : lastIdNode.asText();
                    } catch (JsonProcessingException e) {
                        throw new CheckpointTypeException("Failed to read checkpoint progress for job '"
                                + jobName + "'. Clear checkpoint via admin endpoint: "
                                + "DELETE /actuator/vigil-jobs/" + jobName + "/checkpoint?confirm=true");
                    }
                })
                .orElse(null);
    }

    private LastIdAndState loadLastIdAndState(String stageName, Object initialState) {
        return checkpointManager.load(jobName, runId, stageName)
                .map(entry -> {
                    try {
                        JsonNode root = jackson.readTree(entry.storedValue());
                        JsonNode lastIdNode = root.get("lastId");
                        String lastId = (lastIdNode == null || lastIdNode.isNull()) ? null : lastIdNode.asText();
                        String stateType = root.path("stateType").asText(null);
                        if (stateType == null || stateType.isBlank())
                            throw new CheckpointTypeException("Missing stateType in checkpoint for job '"
                                    + jobName + "'. Clear checkpoint via admin endpoint.");
                        Class<?> type = Class.forName(stateType);
                        Object state = jackson.treeToValue(root.get("state"), type);
                        return new LastIdAndState(lastId, state);
                    } catch (ClassNotFoundException e) {
                        throw new CheckpointTypeException("State type not found: " + e.getMessage()
                                + ". Likely renamed. Clear checkpoint via admin endpoint.");
                    } catch (JsonProcessingException e) {
                        throw new CheckpointTypeException("Failed to deserialize state: " + e.getMessage());
                    }
                })
                .orElse(new LastIdAndState(null, initialState));
    }

    private String writeJson(Object obj) {
        try {
            return jackson.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new CheckpointTypeException("Failed to serialize checkpoint value: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(CheckpointEntry entry) {
        if (entry.storedValue() == null) return null;
        try {
            Class<?> type = Class.forName(entry.valueType());
            return (T) jackson.readValue(entry.storedValue(), type);
        } catch (ClassNotFoundException e) {
            throw new CheckpointTypeException("Checkpoint type not found: " + entry.valueType()
                    + ". Likely renamed. Clear checkpoint via admin endpoint.");
        } catch (JsonProcessingException e) {
            throw new CheckpointTypeException("Failed to deserialize checkpoint value: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <S> S deserializeState(CheckpointEntry entry) {
        try {
            JsonNode root = jackson.readTree(entry.storedValue());
            String stateType = root.path("stateType").asText(null);
            if (stateType == null || stateType.isBlank())
                throw new CheckpointTypeException("Missing stateType in checkpoint for job '"
                        + jobName + "'. Clear checkpoint via admin endpoint.");
            Class<S> type = (Class<S>) Class.forName(stateType);
            return jackson.treeToValue(root.get("state"), type);
        } catch (ClassNotFoundException e) {
            throw new CheckpointTypeException("State type not found: " + e.getMessage()
                    + ". Likely renamed. Clear checkpoint via admin endpoint.");
        } catch (JsonProcessingException e) {
            throw new CheckpointTypeException("Failed to deserialize state: " + e.getMessage());
        }
    }

    private record LastIdAndState(String lastId, Object state) {}
}
