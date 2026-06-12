package io.vigil.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LockAcquisitionTest {

    @Test
    @DisplayName("fencingToken and runId accessors return the values passed to the constructor")
    void accessorsReturnConstructedValues() {
        UUID id = UUID.randomUUID();
        var acquisition = new LockAcquisition(42L, id);

        assertThat(acquisition.fencingToken()).isEqualTo(42L);
        assertThat(acquisition.runId()).isEqualTo(id);
    }

    @Test
    @DisplayName("two acquisitions with identical fields are equal (record equality)")
    void equalityHoldsForSameValues() {
        UUID id = UUID.randomUUID();
        assertThat(new LockAcquisition(1L, id)).isEqualTo(new LockAcquisition(1L, id));
    }

    @Test
    @DisplayName("acquisitions with different fencing tokens are not equal")
    void inequalityOnDifferentToken() {
        UUID id = UUID.randomUUID();
        assertThat(new LockAcquisition(1L, id)).isNotEqualTo(new LockAcquisition(2L, id));
    }
}
