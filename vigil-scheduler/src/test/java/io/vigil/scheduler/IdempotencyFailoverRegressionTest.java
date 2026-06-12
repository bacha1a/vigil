package io.vigil.scheduler;

import io.vigil.core.model.IdempotencyKey;
import io.vigil.scheduler.context.ExactlyOnceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFailoverRegressionTest {

    @AfterEach
    void unbind() {
        ExactlyOnceContext.unbind();
    }

    @Test
    @DisplayName("idempotency key is identical across a failover: same runId, different fencing token")
    void key_isStableAcrossFailover() {
        ExactlyOnceContext.bind("run-1", 5L, "cust-003");
        IdempotencyKey zombie = IdempotencyKey.forMethod(
                ExactlyOnceContext.current().stableId(), ExactlyOnceContext.current().itemId(), "charge");

        ExactlyOnceContext.bind("run-1", 6L, "cust-003");
        IdempotencyKey takeover = IdempotencyKey.forMethod(
                ExactlyOnceContext.current().stableId(), ExactlyOnceContext.current().itemId(), "charge");

        assertThat(takeover)
                .as("same key on both sides of a failover lets an idempotent provider dedup the boundary charge")
                .isEqualTo(zombie);
    }

    @Test
    @DisplayName("a token-based key would differ across a failover (why D18 was needed)")
    void legacyTokenKey_differsAcrossFailover() {
        assertThat(IdempotencyKey.forMethod(5L, "cust-003", "charge"))
                .isNotEqualTo(IdempotencyKey.forMethod(6L, "cust-003", "charge"));
    }

    @Test
    @DisplayName("stableId falls back to the fencing token when no runId is bound")
    void stableId_fallsBackToToken() {
        ExactlyOnceContext.bind(42L, "cust-001");
        assertThat(ExactlyOnceContext.current().stableId()).isEqualTo("42");
    }
}
