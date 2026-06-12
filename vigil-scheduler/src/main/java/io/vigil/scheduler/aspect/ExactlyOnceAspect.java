package io.vigil.scheduler.aspect;

import io.vigil.core.model.IdempotencyKey;
import io.vigil.scheduler.context.ExactlyOnceContext;
import io.vigil.scheduler.context.HttpIdempotencyContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ExactlyOnceAspect {

    private static final Logger log = LoggerFactory.getLogger(ExactlyOnceAspect.class);

    @Around("@annotation(io.vigil.scheduler.ExactlyOnce)")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        String label = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        ExactlyOnceContext ctx = ExactlyOnceContext.current();

        if (ctx == null) {
            log.warn("[Vigil] @ExactlyOnce '{}' called outside a forEach context - "
                    + "no idempotency key will be injected. "
                    + "Move this call inside a forEach/forEachPage lambda.", label);
            return pjp.proceed();
        }

        IdempotencyKey key = IdempotencyKey.forMethod(ctx.stableId(), ctx.itemId(), label);

        log.debug("[Vigil] @ExactlyOnce '{}' key={}", label, key.value());

        HttpIdempotencyContext.bind(key);
        try {
            return pjp.proceed();
        } finally {
            HttpIdempotencyContext.unbind();
        }
    }
}
