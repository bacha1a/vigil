package io.vigil.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;

class VigilJobLifecycleListenerTest {

    @Test
    void defaultMethodsAreNoOpsAndNeverThrow() {
        VigilJobLifecycleListener listener = new VigilJobLifecycleListener() {};

        assertThatNoException().isThrownBy(() -> listener.onLockSkipped("job"));
        assertThatNoException().isThrownBy(() -> listener.onLockAcquired("job"));
        assertThatNoException().isThrownBy(() -> listener.onJobCompleted("job", "SUCCESS", 1L));
        assertThatNoException().isThrownBy(() -> listener.onLockStolen("job"));
        assertThatNoException().isThrownBy(() -> listener.onItemProcessed("job"));
        assertThatNoException().isThrownBy(() -> listener.onCheckpointSaved("job"));
        assertThatNoException().isThrownBy(() -> listener.onFailoverRecovery("job"));
    }
}
