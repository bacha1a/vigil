package io.vigil.core.support;

import io.vigil.core.exception.CheckpointTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointTypeValidatorTest {

    record ValidState(int count, long total, String msg) {}
    record NestedValid(int x, ValidState inner) {}
    record HasList(int count, List<String> items) {}
    record DeepInvalid(int x, HasList inner) {}
    static class MutableClass { int x; }
    enum Color { RED }

    @Nested
    @DisplayName("allowed types")
    class AllowedTypes {

        @Test @DisplayName("null")
        void nullIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(null)); }

        @Test @DisplayName("String")
        void stringIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate("ok")); }

        @Test @DisplayName("Integer")
        void integerIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(42)); }

        @Test @DisplayName("Long")
        void longIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(42L)); }

        @Test @DisplayName("Double")
        void doubleIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(3.14)); }

        @Test @DisplayName("Boolean")
        void booleanIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(true)); }

        @Test @DisplayName("UUID")
        void uuidIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(UUID.randomUUID())); }

        @Test @DisplayName("LocalDate")
        void localDateIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(LocalDate.now())); }

        @Test @DisplayName("LocalDateTime")
        void localDateTimeIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(LocalDateTime.now())); }

        @Test @DisplayName("Instant")
        void instantIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(Instant.now())); }

        @Test @DisplayName("enum constant")
        void enumIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(Color.RED)); }

        @Test @DisplayName("record with primitive fields")
        void recordIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(new ValidState(1, 2L, "x"))); }

        @Test @DisplayName("record nested inside another valid record")
        void nestedRecordIsAllowed() { assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validate(new NestedValid(1, new ValidState(2, 3L, "y")))); }
    }

    @Nested
    @DisplayName("forbidden types")
    class ForbiddenTypes {

        @Test
        @DisplayName("Collection - throws with 'Collection' in message")
        void collectionIsForbidden() {
            assertThatThrownBy(() -> CheckpointTypeValidator.validate(List.of("a")))
                    .isInstanceOf(CheckpointTypeException.class)
                    .hasMessageContaining("Collection");
        }

        @Test
        @DisplayName("Map - throws with 'Map' in message")
        void mapIsForbidden() {
            assertThatThrownBy(() -> CheckpointTypeValidator.validate(Map.of("k", "v")))
                    .isInstanceOf(CheckpointTypeException.class)
                    .hasMessageContaining("Map");
        }

        @Test
        @DisplayName("array - throws CheckpointTypeException")
        void arrayIsForbidden() {
            assertThatThrownBy(() -> CheckpointTypeValidator.validate(new String[]{"a"}))
                    .isInstanceOf(CheckpointTypeException.class);
        }

        @Test
        @DisplayName("mutable class (non-record) - throws with 'record' in message")
        void mutableClassIsForbidden() {
            assertThatThrownBy(() -> CheckpointTypeValidator.validate(new MutableClass()))
                    .isInstanceOf(CheckpointTypeException.class)
                    .hasMessageContaining("record");
        }

        @Test
        @DisplayName("record containing a List field - throws with the field name in message")
        void recordWithListIsForbidden() {
            assertThatThrownBy(() -> CheckpointTypeValidator.validate(new HasList(1, List.of())))
                    .isInstanceOf(CheckpointTypeException.class)
                    .hasMessageContaining("items");
        }

        @Test
        @DisplayName("deeply nested invalid type - throws with the intermediate field name in message")
        void deepNestedInvalidType_throwsWithFieldPath() {
            assertThatThrownBy(() -> CheckpointTypeValidator.validate(new DeepInvalid(1, new HasList(2, List.of()))))
                    .isInstanceOf(CheckpointTypeException.class)
                    .hasMessageContaining("inner");
        }
    }

    @Nested
    @DisplayName("validateClass")
    class ValidateClass {

        @Test
        @DisplayName("valid record class - does not throw")
        void validRecord_doesNotThrow() {
            assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validateClass(ValidState.class));
        }

        @Test
        @DisplayName("null class - does not throw")
        void nullClass_doesNotThrow() {
            assertThatNoException().isThrownBy(() -> CheckpointTypeValidator.validateClass(null));
        }
    }
}
