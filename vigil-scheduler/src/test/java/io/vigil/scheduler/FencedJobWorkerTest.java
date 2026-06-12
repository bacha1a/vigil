package io.vigil.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.exception.LockStolenException;
import io.vigil.core.model.LockAcquisition;
import io.vigil.core.spi.CheckpointManager;
import io.vigil.core.spi.FencedLock;
import io.vigil.scheduler.heartbeat.HeartbeatDaemon;
import io.vigil.scheduler.worker.FencedJobWorker;
import io.vigil.scheduler.worker.WorkerDeps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FencedJobWorkerTest {

    @Mock FencedLock        fencedLock;
    @Mock CheckpointManager checkpointManager;
    @Mock HeartbeatDaemon   heartbeatDaemon;

    public static class Target {
        boolean invoked;

        @FencedScheduled(name = "test-job")
        public void run() { invoked = true; }

        @FencedScheduled(name = "fail-job")
        public void alwaysThrows() { throw new RuntimeException("boom"); }

        @FencedScheduled(name = "stolen-job")
        public void throwsLockStolen() { throw new LockStolenException("stolen"); }

        @FencedScheduled(name = "warn-job", warnOnSkip = true)
        public void runWarnOnSkip() { invoked = true; }
    }

    Target target;

    @BeforeEach
    void setUp() { target = new Target(); }

    private FencedJobWorker workerFor(String methodName) throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod(methodName);
        FencedScheduled ann = method.getAnnotation(FencedScheduled.class);
        var deps = new WorkerDeps(fencedLock, checkpointManager, heartbeatDaemon,
                "pod-1", new ObjectMapper(), null, null, null);
        return FencedJobWorker.forAnnotatedMethod(ann, target, method, deps);
    }

    @Nested
    @DisplayName("when lock not acquired")
    class WhenLockNotAcquired {

        @BeforeEach
        void setUp() {
            when(fencedLock.tryAcquire(any(), any(), any())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("does not invoke the method")
        void doesNotInvokeMethod() throws Exception {
            workerFor("run").run();
            assertThat(target.invoked).isFalse();
        }

        @Test
        @DisplayName("does not register a heartbeat")
        void doesNotRegisterHeartbeat() throws Exception {
            workerFor("run").run();
            verifyNoInteractions(heartbeatDaemon);
        }

        @Test
        @DisplayName("does not release the lock")
        void doesNotReleaseLock() throws Exception {
            workerFor("run").run();
            verify(fencedLock, never()).release(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("when lock acquired")
    class WhenLockAcquired {

        final LockAcquisition acq = new LockAcquisition(42L, UUID.randomUUID());

        @BeforeEach
        void acquireLock() {
            when(fencedLock.tryAcquire(any(), any(), any())).thenReturn(Optional.of(acq));
        }

        @Test
        @DisplayName("invokes the method")
        void invokesMethod() throws Exception {
            workerFor("run").run();
            assertThat(target.invoked).isTrue();
        }

        @Test
        @DisplayName("registers heartbeat with correct fencing token")
        void registersHeartbeatWithFencingToken() throws Exception {
            workerFor("run").run();
            verify(heartbeatDaemon).register(eq("test-job"), eq(42L), any(), any());
        }

        @Test
        @DisplayName("deregisters heartbeat in finally block")
        void deregistersHeartbeatInFinally() throws Exception {
            workerFor("run").run();
            verify(heartbeatDaemon).deregister("test-job");
        }

        @Test
        @DisplayName("releases lock in finally block on success")
        void releasesLockOnSuccess() throws Exception {
            workerFor("run").run();
            verify(fencedLock).release("test-job", 42L);
        }

        @Test
        @DisplayName("releases lock in finally block even when method throws")
        void releasesLockOnException() throws Exception {
            workerFor("alwaysThrows").run();
            verify(fencedLock).release("fail-job", 42L);
        }

        @Test
        @DisplayName("deregisters heartbeat even when method throws")
        void deregistersHeartbeatOnException() throws Exception {
            workerFor("alwaysThrows").run();
            verify(heartbeatDaemon).deregister("fail-job");
        }
    }

    @Nested
    @DisplayName("when method throws LockStolenException")
    class WhenLockStolen {

        final LockAcquisition acq = new LockAcquisition(7L, UUID.randomUUID());

        @BeforeEach
        void acquireLock() {
            when(fencedLock.tryAcquire(any(), any(), any())).thenReturn(Optional.of(acq));
        }

        @Test
        @DisplayName("releases lock in finally")
        void releasesLock() throws Exception {
            workerFor("throwsLockStolen").run();
            verify(fencedLock).release("stolen-job", 7L);
        }

        @Test
        @DisplayName("deregisters heartbeat in finally")
        void deregistersHeartbeat() throws Exception {
            workerFor("throwsLockStolen").run();
            verify(heartbeatDaemon).deregister("stolen-job");
        }
    }
}
