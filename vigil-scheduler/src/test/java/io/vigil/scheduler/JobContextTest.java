package io.vigil.scheduler;

import io.vigil.scheduler.context.ExactlyOnceContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.exception.CheckpointTypeException;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.model.CheckpointStatus;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.support.StageKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobContextTest {

    @Mock
    CheckpointManager checkpointManager;

    static final String JOB    = "test-job";
    static final UUID   RUN_ID = UUID.randomUUID();
    static final long   TOKEN  = 42L;

    JobContext ctx;

    private enum Stage { STEP, LOOP, ACCUMULATE, PAGE, PAGE_STATE }

    @BeforeEach
    void setUp() {
        ctx = new JobContext(JOB, RUN_ID, TOKEN, checkpointManager, new ObjectMapper());
    }

    @Nested
    @DisplayName("step (with return value)")
    class StepWithValue {

        @Test
        @DisplayName("first run - executes the supplier and saves a COMPLETE checkpoint with value type")
        void firstRun_executesAndSavesCheckpoint() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(false);

            AtomicBoolean called = new AtomicBoolean(false);
            String result = ctx.step(Stage.STEP, () -> {
                called.set(true);
                return "result";
            });

            assertThat(called.get()).isTrue();
            assertThat(result).isEqualTo("result");

            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager).save(captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(CheckpointStatus.COMPLETE);
            assertThat(captor.getValue().valueType()).isEqualTo("java.lang.String");
        }

        @Test
        @DisplayName("on resume - skips the supplier and returns the stored deserialized value")
        void onResume_skipsSupplierAndReturnsStoredValue() {
            CheckpointEntry stored = new CheckpointEntry(JOB, RUN_ID, key(Stage.STEP),
                    CheckpointStatus.COMPLETE, "\"stored-result\"", "java.lang.String", TOKEN);
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(true);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.STEP))).thenReturn(Optional.of(stored));

            AtomicBoolean called = new AtomicBoolean(false);
            String result = ctx.step(Stage.STEP, () -> {
                called.set(true);
                return "fresh";
            });

            assertThat(called.get()).isFalse();
            assertThat(result).isEqualTo("stored-result");
            verify(checkpointManager, never()).save(any());
        }

        @Test
        @DisplayName("null return value - saves a COMPLETE checkpoint with null storedValue and valueType")
        void nullReturn_savesNullCheckpoint() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(false);

            Object result = ctx.step(Stage.STEP, () -> null);

            assertThat(result).isNull();
            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager).save(captor.capture());
            assertThat(captor.getValue().storedValue()).isNull();
            assertThat(captor.getValue().valueType()).isNull();
            assertThat(captor.getValue().status()).isEqualTo(CheckpointStatus.COMPLETE);
        }

        @Test
        @DisplayName("forbidden return type (List) - throws CheckpointTypeException before saving")
        void forbiddenReturnType_throwsCheckpointTypeException() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(false);

            assertThatThrownBy(() -> ctx.step(Stage.STEP, () -> List.of("a", "b")))
                    .isInstanceOf(CheckpointTypeException.class);
        }
    }

    @Nested
    @DisplayName("step (void)")
    class StepVoid {

        @Test
        @DisplayName("first run - executes the runnable and saves a COMPLETE checkpoint with null value")
        void firstRun_executesAndMarksComplete() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(false);

            AtomicBoolean called = new AtomicBoolean(false);
            ctx.step(Stage.STEP, () -> called.set(true));

            assertThat(called.get()).isTrue();

            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager).save(captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(CheckpointStatus.COMPLETE);
            assertThat(captor.getValue().storedValue()).isNull();
        }

        @Test
        @DisplayName("on resume - skips the runnable entirely")
        void onResume_skipsRunnable() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(true);

            AtomicBoolean called = new AtomicBoolean(false);
            ctx.step(Stage.STEP, () -> called.set(true));

            assertThat(called.get()).isFalse();
            verify(checkpointManager, never()).save(any());
        }
    }

    @Nested
    @DisplayName("forEach")
    class ForEach {

        @Test
        @DisplayName("first run - processes all items in order")
        void firstRun_processesAllItems() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.empty());

            List<String> processed = new ArrayList<>();
            ctx.forEach(Stage.LOOP, List.of("a", "b", "c"), item -> item,
                    (item, token) -> processed.add(item));

            assertThat(processed).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("on resume - skips already-processed items, continues from last checkpoint")
        void onResume_skipsProcessedItems() {
            String progressJson = "{\"lastId\":\"b\"}";
            CheckpointEntry inProgress = new CheckpointEntry(JOB, RUN_ID, key(Stage.LOOP),
                    CheckpointStatus.IN_PROGRESS, progressJson, null, TOKEN);
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.of(inProgress));

            List<String> processed = new ArrayList<>();
            ctx.forEach(Stage.LOOP, List.of("a", "b", "c"), item -> item,
                    (item, token) -> processed.add(item));

            assertThat(processed).containsExactly("c");
        }

        @Test
        @DisplayName("when stage is already complete - skips all items without saving")
        void whenComplete_skipsAllItems() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(true);

            AtomicBoolean called = new AtomicBoolean(false);
            ctx.forEach(Stage.LOOP, List.of("a", "b", "c"), item -> item,
                    (item, token) -> called.set(true));

            assertThat(called.get()).isFalse();
            verify(checkpointManager, never()).save(any());
        }

        @Test
        @DisplayName("empty item list - immediately marks stage COMPLETE")
        void emptyList_marksComplete() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.empty());

            ctx.forEach(Stage.LOOP, List.<String>of(), item -> item, (item, token) -> {});

            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager).save(captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(CheckpointStatus.COMPLETE);
        }

        @Test
        @DisplayName("ExactlyOnceContext is bound during the lambda and unbound after it returns")
        void bindsExactlyOnceContext_duringLambda() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.empty());

            AtomicReference<ExactlyOnceContext> captured = new AtomicReference<>();
            ctx.forEach(Stage.LOOP, List.of("item-1"), item -> item,
                    (item, token) -> captured.set(ExactlyOnceContext.current()));

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().fencingToken()).isEqualTo(TOKEN);
            assertThat(captured.get().itemId()).isEqualTo("item-1");
            assertThat(ExactlyOnceContext.current()).isNull();
        }

        @Test
        @DisplayName("lambda throws - ExactlyOnceContext is unbound regardless")
        void lambdaThrows_exactlyOnceContextIsUnbound() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    ctx.forEach(Stage.LOOP, List.of("item-1"), item -> item,
                            (item, token) -> { throw new RuntimeException("boom"); }))
                    .isInstanceOf(RuntimeException.class);

            assertThat(ExactlyOnceContext.current()).isNull();
        }
    }

    @Nested
    @DisplayName("forEach (Consumer overload - no fencing token)")
    class ForEachConsumer {

        @Test
        @DisplayName("processes all items without requiring token in lambda")
        void processesAllItems() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.empty());

            List<String> processed = new ArrayList<>();
            ctx.forEach(Stage.LOOP, List.of("a", "b", "c"), item -> item, item -> processed.add(item));

            assertThat(processed).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("idFn accepts non-String type via toString()")
        void acceptsNonStringId() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(Optional.empty());

            AtomicLong sum = new AtomicLong(0);
            ctx.forEach(Stage.LOOP, List.of(1L, 2L, 3L), item -> item, sum::addAndGet);

            assertThat(sum.get()).isEqualTo(6L);
        }
    }

    @Nested
    @DisplayName("forEachWithState")
    class ForEachWithState {

        @Test
        @DisplayName("first run - accumulates state across all items and saves COMPLETE checkpoint")
        void firstRun_accumulatesCorrectly() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(Optional.empty());

            Long result = ctx.forEachWithState(Stage.ACCUMULATE,
                    List.of("a", "b", "c"), item -> item, 0L,
                    (item, token, state) -> state + 1L);

            assertThat(result).isEqualTo(3L);

            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager, atLeastOnce()).save(captor.capture());
            CheckpointEntry last = captor.getAllValues().getLast();
            assertThat(last.status()).isEqualTo(CheckpointStatus.COMPLETE);
        }

        @Test
        @DisplayName("on resume - restores state from checkpoint and continues from last item")
        void onResume_restoresStateAndContinues() {
            String progressJson = "{\"lastId\":\"b\",\"state\":2,\"stateType\":\"java.lang.Long\"}";
            CheckpointEntry inProgress = new CheckpointEntry(JOB, RUN_ID, key(Stage.ACCUMULATE),
                    CheckpointStatus.IN_PROGRESS, progressJson, null, TOKEN);
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(Optional.of(inProgress));

            List<String> processed = new ArrayList<>();
            Long result = ctx.forEachWithState(Stage.ACCUMULATE,
                    List.of("a", "b", "c"), item -> item, 0L,
                    (item, token, state) -> {
                        processed.add(item);
                        return state + 1L;
                    });

            assertThat(processed).containsExactly("c");
            assertThat(result).isEqualTo(3L);
        }

        @Test
        @DisplayName("when stage is already complete - returns stored state without calling the action")
        void whenComplete_returnsStoredState() {
            String json = "{\"lastId\":null,\"state\":10,\"stateType\":\"java.lang.Long\"}";
            CheckpointEntry complete = new CheckpointEntry(JOB, RUN_ID, key(Stage.ACCUMULATE),
                    CheckpointStatus.COMPLETE, json, null, TOKEN);
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(true);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(Optional.of(complete));

            AtomicBoolean called = new AtomicBoolean(false);
            Long result = ctx.forEachWithState(Stage.ACCUMULATE, List.of("a"), item -> item, 0L,
                    (item, token, state) -> { called.set(true); return state + 1L; });

            assertThat(called.get()).isFalse();
            assertThat(result).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("forEachPage")
    class ForEachPage {

        @Test
        @DisplayName("first run - drives page loader across all pages and processes every item")
        void firstRun_processesAllPages() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.PAGE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.PAGE))).thenReturn(Optional.empty());

            List<String> processed = new ArrayList<>();
            ctx.forEachPage(Stage.PAGE,
                    cursor -> cursor.isEmpty() ? List.of("a", "b") : cursor.get().equals("b") ? List.of("c") : List.of(),
                    item -> item,
                    (item, token) -> processed.add(item));

            assertThat(processed).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("on resume - starts page loader from the last saved cursor")
        void onResume_startsFromLastCheckpoint() {
            String progressJson = "{\"lastId\":\"b\"}";
            CheckpointEntry inProgress = new CheckpointEntry(JOB, RUN_ID, key(Stage.PAGE),
                    CheckpointStatus.IN_PROGRESS, progressJson, null, TOKEN);
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.PAGE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.PAGE))).thenReturn(Optional.of(inProgress));

            List<String> processed = new ArrayList<>();
            List<Optional<String>> cursors = new ArrayList<>();
            ctx.forEachPage(Stage.PAGE,
                    cursor -> {
                        cursors.add(cursor);
                        return cursor.filter("b"::equals).isPresent() ? List.of("c") : List.of();
                    },
                    item -> item,
                    (item, token) -> processed.add(item));

            assertThat(cursors.getFirst()).isEqualTo(of("b"));
            assertThat(processed).containsExactly("c");
        }
    }

    @Nested
    @DisplayName("forEachWithState (BiFunction overload - no fencing token)")
    class ForEachWithStateBiFunction {

        @Test
        @DisplayName("accumulates state without requiring token in lambda")
        void accumulatesWithoutToken() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.ACCUMULATE))).thenReturn(Optional.empty());

            Long result = ctx.forEachWithState(Stage.ACCUMULATE,
                    List.of("a", "b", "c"), item -> item, 0L,
                    (item, state) -> state + 1L);

            assertThat(result).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("forEachPage (Consumer overload - no fencing token)")
    class ForEachPageConsumer {

        @Test
        @DisplayName("processes all pages without requiring token in lambda")
        void processesAllPages() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.PAGE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.PAGE))).thenReturn(Optional.empty());

            List<String> processed = new ArrayList<>();
            ctx.forEachPage(Stage.PAGE,
                    cursor -> cursor.isEmpty() ? List.of("a", "b") : cursor.get().equals("b") ? List.of("c") : List.of(),
                    item -> item,
                    item -> processed.add(item));

            assertThat(processed).containsExactly("a", "b", "c");
        }
    }

    @Nested
    @DisplayName("forEachPageWithState")
    class ForEachPageWithState {

        @Test
        @DisplayName("first run - accumulates state across all pages and saves COMPLETE checkpoint")
        void firstRun_accumulatesAcrossPages() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.PAGE_STATE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.PAGE_STATE))).thenReturn(Optional.empty());

            Long result = ctx.forEachPageWithState(Stage.PAGE_STATE,
                    cursor -> cursor.isEmpty() ? List.of("a", "b") : cursor.get().equals("b") ? List.of("c") : List.of(),
                    item -> item,
                    0L,
                    (item, token, state) -> state + 1L);

            assertThat(result).isEqualTo(3L);

            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues().getLast().status()).isEqualTo(CheckpointStatus.COMPLETE);
        }
    }

    @Nested
    @DisplayName("forEachPageWithState (BiFunction overload - no fencing token)")
    class ForEachPageWithStateBiFunction {

        @Test
        @DisplayName("accumulates state across pages without requiring token in lambda")
        void accumulatesWithoutToken() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.PAGE_STATE))).thenReturn(false);
            when(checkpointManager.load(JOB, RUN_ID, key(Stage.PAGE_STATE))).thenReturn(Optional.empty());

            Long result = ctx.forEachPageWithState(Stage.PAGE_STATE,
                    cursor -> cursor.isEmpty() ? List.of("a", "b") : cursor.get().equals("b") ? List.of("c") : List.of(),
                    item -> item,
                    0L,
                    (item, state) -> state + 1L);

            assertThat(result).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("manual control (isStageComplete / completeStage)")
    class ManualControl {

        @Test
        @DisplayName("isStageComplete delegates to CheckpointManager.isComplete")
        void isStageComplete_delegatesToCheckpointManager() {
            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.STEP))).thenReturn(true);
            assertThat(ctx.isStageComplete(Stage.STEP)).isTrue();

            when(checkpointManager.isComplete(JOB, RUN_ID, key(Stage.LOOP))).thenReturn(false);
            assertThat(ctx.isStageComplete(Stage.LOOP)).isFalse();
        }

        @Test
        @DisplayName("completeStage saves a COMPLETE checkpoint with null value for the given stage")
        void completeStage_savesCompleteCheckpoint() {
            ctx.completeStage(Stage.STEP);

            ArgumentCaptor<CheckpointEntry> captor = ArgumentCaptor.forClass(CheckpointEntry.class);
            verify(checkpointManager).save(captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(CheckpointStatus.COMPLETE);
            assertThat(captor.getValue().storedValue()).isNull();
            assertThat(captor.getValue().stageName()).isEqualTo(key(Stage.STEP));
            assertThat(captor.getValue().fencingToken()).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("getFencingToken returns the value passed to the constructor")
        void getFencingToken_returnsConstructorValue() {
            assertThat(ctx.getFencingToken()).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("getRunId returns the UUID passed to the constructor")
        void getRunId_returnsConstructorValue() {
            assertThat(ctx.getRunId()).isEqualTo(RUN_ID);
        }
    }

    @Nested
    @DisplayName("thread-local binding")
    class ThreadLocalBinding {

        @Test
        @DisplayName("bind/current/unbind - current() returns the bound context, throws after unbind")
        void bindCurrentUnbind_lifecycle() {
            JobContext.bind(ctx);
            assertThat(JobContext.current()).isSameAs(ctx);
            JobContext.unbind();
            assertThatThrownBy(JobContext::current).isInstanceOf(IllegalStateException.class);
        }
    }

    private String key(Stage stage) {
        return StageKeys.toDbKey(stage);
    }
}
