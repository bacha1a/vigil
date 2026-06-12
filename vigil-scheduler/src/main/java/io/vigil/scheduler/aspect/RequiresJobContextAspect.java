package io.vigil.scheduler.aspect;

import io.vigil.scheduler.JobContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RequiresJobContextAspect {

    @Around("@annotation(io.vigil.scheduler.RequiresJobContext)")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        if (!JobContext.isBound()) {
            String method = ((MethodSignature) pjp.getSignature()).getMethod().getDeclaringClass().getSimpleName()
                    + "." + pjp.getSignature().getName();
            throw new IllegalStateException(
                    "Method [" + method + "] is annotated @RequiresJobContext but was called outside " +
                    "a @FencedScheduled job");
        }
        return pjp.proceed();
    }
}
