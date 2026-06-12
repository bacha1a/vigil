package io.vigil.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointEntryTest {

    @Test
    @DisplayName("all accessors return the values passed to the constructor")
    void accessorsReturnConstructedValues() {
        UUID runId = UUID.randomUUID();
        var entry = new CheckpointEntry(
                "billing-job", runId, "Stage.CHARGE",
                CheckpointStatus.IN_PROGRESS, "{\"page\":1}", "java.lang.Integer", 7L
        );

        assertThat(entry.jobName()).isEqualTo("billing-job");
        assertThat(entry.runId()).isEqualTo(runId);
        assertThat(entry.stageName()).isEqualTo("Stage.CHARGE");
        assertThat(entry.status()).isEqualTo(CheckpointStatus.IN_PROGRESS);
        assertThat(entry.storedValue()).isEqualTo("{\"page\":1}");
        assertThat(entry.valueType()).isEqualTo("java.lang.Integer");
        assertThat(entry.fencingToken()).isEqualTo(7L);
    }

    @Test
    @DisplayName("two entries with identical fields are equal (record equality)")
    void equalityHoldsForSameValues() {
        UUID runId = UUID.randomUUID();
        var a = new CheckpointEntry("job", runId, "Stage.X", CheckpointStatus.COMPLETE, null, null, 1L);
        var b = new CheckpointEntry("job", runId, "Stage.X", CheckpointStatus.COMPLETE, null, null, 1L);

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("null storedValue and null valueType are permitted")
    void nullStoredValueAndTypeAreAllowed() {
        UUID runId = UUID.randomUUID();
        var entry = new CheckpointEntry("job", runId, "Stage.X", CheckpointStatus.COMPLETE, null, null, 1L);

        assertThat(entry.storedValue()).isNull();
        assertThat(entry.valueType()).isNull();
    }

    @Test
    @DisplayName("status enum names match the DB column values")
    void statusEnumNamesMatchDbValues() {
        assertThat(CheckpointStatus.IN_PROGRESS.name()).isEqualTo("IN_PROGRESS");
        assertThat(CheckpointStatus.COMPLETE.name()).isEqualTo("COMPLETE");
    }
}
