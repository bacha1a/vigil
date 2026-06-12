package io.vigil.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VigilJobDefinitionTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("define() rejects blank name")
        void rejectsBlankName() {
            assertThatThrownBy(() -> VigilJobDefinition.define(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VigilJobDefinition.define("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VigilJobDefinition.define(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("default values are set when only name is supplied")
        void defaultsApplied() {
            var def = VigilJobDefinition.define("job").run(() -> {});

            assertThat(def.name()).isEqualTo("job");
            assertThat(def.cron()).isEmpty();
            assertThat(def.fixedRateMs()).isEqualTo(-1L);
            assertThat(def.zone()).isEqualTo("UTC");
            assertThat(def.lockTtlSeconds()).isEqualTo(300L);
            assertThat(def.warnOnSkip()).isTrue();
            assertThat(def.action()).isNotNull();
        }

        @Test
        @DisplayName("all builder setters are honored")
        void buildersHonored() {
            Consumer<JobContext> action = ctx -> {};

            var def = VigilJobDefinition.define("job")
                    .cron("0 0 * * * *")
                    .fixedRateMs(60_000L)
                    .zone("Asia/Tbilisi")
                    .lockTtlSeconds(120L)
                    .warnOnSkip(false)
                    .run(action);

            assertThat(def.cron()).isEqualTo("0 0 * * * *");
            assertThat(def.fixedRateMs()).isEqualTo(60_000L);
            assertThat(def.zone()).isEqualTo("Asia/Tbilisi");
            assertThat(def.lockTtlSeconds()).isEqualTo(120L);
            assertThat(def.warnOnSkip()).isFalse();
            assertThat(def.action()).isSameAs(action);
        }

        @Test
        @DisplayName("run(Runnable) wraps the runnable in a JobContext consumer")
        void runRunnableWraps() {
            var counter = new AtomicInteger(0);

            var def = VigilJobDefinition.define("job").run((Runnable) counter::incrementAndGet);

            def.action().accept(null);
            assertThat(counter.get()).isEqualTo(1);
        }
    }
}
