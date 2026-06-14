package io.vigil.core.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VigilExceptionsTest {

    @Test
    void checkpointTypeException() {
        CheckpointTypeException e1 = new CheckpointTypeException("bad type");
        assertThat(e1.getMessage()).isEqualTo("bad type");
        assertThat(e1.getCause()).isNull();

        Throwable cause = new IllegalStateException("inner");
        CheckpointTypeException e2 = new CheckpointTypeException("bad type", cause);
        assertThat(e2.getMessage()).isEqualTo("bad type");
        assertThat(e2.getCause()).isSameAs(cause);
    }

    @Test
    void lockStolenException() {
        LockStolenException e1 = new LockStolenException("lock stolen");
        assertThat(e1.getMessage()).isEqualTo("lock stolen");
        assertThat(e1.getCause()).isNull();

        Throwable cause = new RuntimeException("inner");
        LockStolenException e2 = new LockStolenException("lock stolen", cause);
        assertThat(e2.getMessage()).isEqualTo("lock stolen");
        assertThat(e2.getCause()).isSameAs(cause);
    }

    @Test
    void vigilConfigurationException() {
        VigilConfigurationException e1 = new VigilConfigurationException("misconfigured");
        assertThat(e1.getMessage()).isEqualTo("misconfigured");
        assertThat(e1.getCause()).isNull();

        Throwable cause = new IllegalArgumentException("inner");
        VigilConfigurationException e2 = new VigilConfigurationException("misconfigured", cause);
        assertThat(e2.getMessage()).isEqualTo("misconfigured");
        assertThat(e2.getCause()).isSameAs(cause);
    }
}
