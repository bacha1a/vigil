package io.vigil.scheduler;

import io.vigil.scheduler.heartbeat.HeartbeatDaemon;

import io.vigil.core.spi.FencedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeartbeatDaemonTest {

    FencedLock      fencedLock;
    HeartbeatDaemon daemon;

    @BeforeEach
    void setUp() {
        fencedLock = mock(FencedLock.class);
        daemon = new HeartbeatDaemon(fencedLock);
    }

    @AfterEach
    void tearDown() {
        daemon.stop();
    }

    @Nested
    @DisplayName("renewal")
    class Renewal {

        @Test
        @DisplayName("successful renewal does not interrupt the job thread")
        void success_doesNotInterruptThread() throws InterruptedException {
            when(fencedLock.tryRenew(anyString(), anyLong(), any(Duration.class))).thenReturn(true);
            Thread jobThread = new Thread(() -> {});
            daemon.register("job", 1L, jobThread, Duration.ofMillis(10));
            daemon.start();

            Thread.sleep(3500);

            assertThat(jobThread.isInterrupted()).isFalse();
        }

        @Test
        @DisplayName("failed renewal interrupts the job thread")
        void failure_interruptsThread() throws InterruptedException {
            when(fencedLock.tryRenew(anyString(), anyLong(), any(Duration.class))).thenReturn(false);

            AtomicBoolean wasInterrupted = new AtomicBoolean(false);
            Thread jobThread = new Thread(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                }
            });
            jobThread.start();
            daemon.register("job", 1L, jobThread, Duration.ofMillis(10));
            daemon.start();

            Thread.sleep(2000);

            assertThat(wasInterrupted.get()).isTrue();
        }

        @Test
        @DisplayName("when one job's renewal fails, other registered jobs continue unaffected")
        void oneJobFails_otherJobContinues() throws InterruptedException {
            when(fencedLock.tryRenew(eq("job-a"), eq(1L), any(Duration.class))).thenReturn(false);
            when(fencedLock.tryRenew(eq("job-b"), eq(2L), any(Duration.class))).thenReturn(true);

            AtomicBoolean threadAInterrupted = new AtomicBoolean(false);
            AtomicBoolean threadBInterrupted = new AtomicBoolean(false);

            Thread jobThreadA = new Thread(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) { threadAInterrupted.set(true); }
            });
            Thread jobThreadB = new Thread(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) { threadBInterrupted.set(true); }
            });
            jobThreadA.start();
            jobThreadB.start();

            daemon.register("job-a", 1L, jobThreadA, Duration.ofMillis(10));
            daemon.register("job-b", 2L, jobThreadB, Duration.ofMillis(10));
            daemon.start();

            Thread.sleep(2000);

            assertThat(threadAInterrupted.get()).isTrue();
            assertThat(threadBInterrupted.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("deregister")
    class Deregister {

        @Test
        @DisplayName("deregistered job is never renewed")
        void deregisteredJob_isNeverRenewed() throws InterruptedException {
            daemon.register("job", 1L, new Thread(() -> {}), Duration.ofMillis(10));
            daemon.deregister("job");
            daemon.start();

            Thread.sleep(50);

            verify(fencedLock, never()).tryRenew(anyString(), anyLong(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("stop")
    class Stop {

        @Test
        @DisplayName("releases all held locks when the daemon is stopped")
        void releasesAllLocks() {
            daemon.start();
            daemon.register("job-a", 1L, new Thread(() -> {}), Duration.ofSeconds(60));
            daemon.register("job-b", 2L, new Thread(() -> {}), Duration.ofSeconds(60));

            daemon.stop();

            verify(fencedLock).release("job-a", 1L);
            verify(fencedLock).release("job-b", 2L);
        }
    }
}
