package io.vigil.scheduler;

import io.vigil.scheduler.advisor.ExactlyOnceStartupValidator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ExactlyOnceStartupValidatorTest {

    static class AnnotatedService {
        @ExactlyOnce
        public void charge() {}

        @ExactlyOnce
        public void notify_() {}
    }

    @Configuration
    static class ValidatorConfig {
        @Bean
        ExactlyOnceStartupValidator exactlyOnceStartupValidator(
                org.springframework.context.ApplicationContext ctx,
                org.springframework.core.env.Environment env) {
            return new ExactlyOnceStartupValidator(ctx, env);
        }
    }

    @Nested
    @DisplayName("when @ExactlyOnce bean is present")
    class WhenAnnotatedBeanPresent {

        @Test
        @DisplayName("warns about cooperative guarantee for each annotated method")
        void warnsAboutCooperativeGuarantee() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .withBean(AnnotatedService.class)
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).anyMatch(w -> w.contains("cooperative"));
                    });
        }

        @Test
        @DisplayName("warning includes the method label for each annotated method")
        void warningIncludesMethodLabel() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .withBean(AnnotatedService.class)
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).anyMatch(w -> w.contains("AnnotatedService.charge"));
                        assertThat(warnings).anyMatch(w -> w.contains("AnnotatedService.notify_"));
                    });
        }

        @Test
        @DisplayName("produces one warning per annotated method")
        void oneWarningPerMethod() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .withBean(AnnotatedService.class)
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).hasSize(2);
                    });
        }
    }

    @Nested
    @DisplayName("when virtual threads are enabled")
    class WhenVirtualThreadsEnabled {

        @Test
        @DisplayName("warns about ThreadLocal propagation limitation for each method")
        void warnsAboutThreadLocalLimitation() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .withBean(AnnotatedService.class)
                    .withPropertyValues("spring.threads.virtual.enabled=true")
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).anyMatch(w -> w.contains("ThreadLocal"));
                    });
        }

        @Test
        @DisplayName("produces two warnings per method when virtual threads are enabled")
        void twoWarningsPerMethodWhenVirtualThreads() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .withBean(AnnotatedService.class)
                    .withPropertyValues("spring.threads.virtual.enabled=true")
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).hasSize(4);
                    });
        }

        @Test
        @DisplayName("does not warn about ThreadLocal when virtual threads are disabled")
        void noThreadLocalWarningWhenVirtualThreadsDisabled() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .withBean(AnnotatedService.class)
                    .withPropertyValues("spring.threads.virtual.enabled=false")
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).noneMatch(w -> w.contains("ThreadLocal"));
                    });
        }
    }

    @Nested
    @DisplayName("when no @ExactlyOnce beans are present")
    class WhenNoAnnotatedBeans {

        @Test
        @DisplayName("produces no warnings")
        void noWarnings() {
            new ApplicationContextRunner()
                    .withUserConfiguration(ValidatorConfig.class)
                    .run(ctx -> {
                        var warnings = ctx.getBean(ExactlyOnceStartupValidator.class).validate();
                        assertThat(warnings).isEmpty();
                    });
        }
    }
}
