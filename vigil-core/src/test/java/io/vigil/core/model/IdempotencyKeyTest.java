package io.vigil.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyTest {

    @Test
    @DisplayName("of - produces 'vigil_{token}_{itemId}' format")
    void ofProducesCorrectFormat() {
        assertThat(IdempotencyKey.of(42L, "cust-500").value())
                .isEqualTo("vigil_42_cust-500");
    }

    @Test
    @DisplayName("of - works with minimal values (token=1, single-char id)")
    void ofWithSmallValues() {
        assertThat(IdempotencyKey.of(1L, "x").value()).isEqualTo("vigil_1_x");
    }

    @Test
    @DisplayName("of - same arguments produce equal records")
    void sameArgsProduceEqualRecords() {
        assertThat(IdempotencyKey.of(42L, "cust-500"))
                .isEqualTo(IdempotencyKey.of(42L, "cust-500"));
    }

    @Test
    @DisplayName("forMethod - appends method name as a fourth segment")
    void forMethodIncludesMethodName() {
        assertThat(IdempotencyKey.forMethod(42L, "cust-500", "StripeService.charge").value())
                .isEqualTo("vigil_42_cust-500_StripeService.charge");
    }

    @Test
    @DisplayName("of - different fencing tokens produce different keys")
    void differentTokensProduceDifferentKeys() {
        assertThat(IdempotencyKey.of(1L, "item")).isNotEqualTo(IdempotencyKey.of(2L, "item"));
    }

    @Test
    @DisplayName("of - different item IDs produce different keys")
    void differentItemIdsProduceDifferentKeys() {
        assertThat(IdempotencyKey.of(42L, "a")).isNotEqualTo(IdempotencyKey.of(42L, "b"));
    }

    @Test
    @DisplayName("forMethod - same arguments produce equal records")
    void forMethodSameArgs_producesEqualRecords() {
        assertThat(IdempotencyKey.forMethod(1L, "x", "m"))
                .isEqualTo(IdempotencyKey.forMethod(1L, "x", "m"));
    }
}
