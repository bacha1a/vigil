package io.vigil.scheduler;

import io.vigil.core.model.IdempotencyKey;
import io.vigil.scheduler.aspect.ExactlyOnceAspect;
import io.vigil.scheduler.context.ExactlyOnceContext;
import io.vigil.scheduler.context.HttpIdempotencyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.AopTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(ExactlyOnceAspectTest.Config.class)
class ExactlyOnceAspectTest {

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {
        @Bean ExactlyOnceAspect exactlyOnceAspect() { return new ExactlyOnceAspect(); }
        @Bean AnnotatedService  annotatedService()  { return new AnnotatedService(); }
    }

    static class AnnotatedService {
        final AtomicReference<IdempotencyKey> capturedKey = new AtomicReference<>();

        @ExactlyOnce
        public void charge() {
            capturedKey.set(HttpIdempotencyContext.current());
        }

        @ExactlyOnce
        public void notify_() {
            capturedKey.set(HttpIdempotencyContext.current());
        }

        @ExactlyOnce
        public void alwaysThrows() {
            throw new RuntimeException("boom");
        }
    }

    @Autowired AnnotatedService service;

    AnnotatedService target() {
        return AopTestUtils.getTargetObject(service);
    }

    @AfterEach
    void cleanup() {
        ExactlyOnceContext.unbind();
        target().capturedKey.set(null);
    }

    @Nested
    @DisplayName("inside forEach context")
    class InsideContext {

        @Test
        @DisplayName("binds an idempotency key with token, itemId, and method label")
        void bindsIdempotencyKey_withCorrectFormat() {
            ExactlyOnceContext.bind(42L, "cust-500");

            service.charge();

            assertThat(target().capturedKey.get()).isNotNull();
            assertThat(target().capturedKey.get().value())
                    .isEqualTo("vigil_42_cust-500_AnnotatedService.charge");
        }

        @Test
        @DisplayName("method label uses declaring class simple name and method name")
        void methodLabel_usesClassAndMethodName() {
            ExactlyOnceContext.bind(7L, "order-99");

            service.notify_();

            assertThat(target().capturedKey.get().value())
                    .isEqualTo("vigil_7_order-99_AnnotatedService.notify_");
        }

        @Test
        @DisplayName("HttpIdempotencyContext is unbound after the method returns normally")
        void httpIdempotencyContext_unboundAfterNormalReturn() {
            ExactlyOnceContext.bind(1L, "item-1");

            service.charge();

            assertThat(HttpIdempotencyContext.current()).isNull();
        }

        @Test
        @DisplayName("HttpIdempotencyContext is unbound even when the method throws")
        void httpIdempotencyContext_unboundOnException() {
            ExactlyOnceContext.bind(1L, "item-1");

            assertThatThrownBy(() -> service.alwaysThrows())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("boom");

            assertThat(HttpIdempotencyContext.current()).isNull();
        }
    }

    @Nested
    @DisplayName("outside forEach context")
    class OutsideContext {

        @Test
        @DisplayName("method executes normally - no idempotency key injected")
        void executesNormally_noKeyInjected() {
            service.charge();

            assertThat(target().capturedKey.get()).isNull();
        }

        @Test
        @DisplayName("HttpIdempotencyContext remains null after the call")
        void httpIdempotencyContext_remainsNull() {
            service.charge();

            assertThat(HttpIdempotencyContext.current()).isNull();
        }

        @Test
        @DisplayName("ExactlyOnceContext remains null after the call")
        void exactlyOnceContext_remainsNull() {
            service.charge();

            assertThat(ExactlyOnceContext.current()).isNull();
        }
    }
}
