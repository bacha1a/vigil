package io.vigil.scheduler;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FencedScheduled {
    String name();
    String cron() default "";
    long   fixedRateMs() default -1;
    String zone() default "UTC";
    int    lockTtlSeconds() default 300;
    boolean warnOnSkip() default true;
}
