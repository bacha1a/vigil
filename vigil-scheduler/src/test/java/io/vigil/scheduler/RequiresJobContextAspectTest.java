package io.vigil.scheduler;

import io.vigil.scheduler.aspect.RequiresJobContextAspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vigil.core.model.CheckpointEntry;
import io.vigil.core.spi.CheckpointManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(RequiresJobContextAspectTest.Config.class)
class RequiresJobContextAspectTest {

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {
        @Bean RequiresJobContextAspect requiresJobContextAspect() { return new RequiresJobContextAspect(); }
        @Bean ScopedService            scopedService()             { return new ScopedService(); }
    }

    static class ScopedService {
        boolean wasCalled = false;

        @RequiresJobContext
        public String process() {
            wasCalled = true;
            return "ok";
        }

        @RequiresJobContext
        public void processVoid(String a, int b) {
            wasCalled = true;
        }
    }

    @Autowired ScopedService service;

    static final CheckpointManager NO_OP = new CheckpointManager() {
        public void save(CheckpointEntry e) {}
        public Optional<CheckpointEntry> load(String j, UUID r, String s) { return Optional.empty(); }
        public boolean isComplete(String j, UUID r, String s) { return false; }
        public java.util.List<String> stageNames(String j, UUID r) { return java.util.List.of(); }
    };

    JobContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new JobContext("test-job", UUID.randomUUID(), 1L, NO_OP, new ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        JobContext.unbind();
    }

    @Nested
    @DisplayName("inside job context")
    class InsideContext {

        @BeforeEach
        void bind() {
            JobContext.bind(ctx);
        }

        @Test
        @DisplayName("method proceeds and returns result")
        void proceedsAndReturnsResult() {
            String result = service.process();

            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("method with multiple parameters proceeds normally")
        void multipleParameters_proceedsNormally() {
            service.processVoid("hello", 42);
        }
    }

    @Nested
    @DisplayName("outside job context")
    class OutsideContext {

        @Test
        @DisplayName("throws IllegalStateException with method name in message")
        void throwsWithMethodName() {
            assertThatThrownBy(() -> service.process())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ScopedService.process")
                    .hasMessageContaining("@RequiresJobContext")
                    .hasMessageContaining("@FencedScheduled");
        }

        @Test
        @DisplayName("throws for void method with multiple parameters")
        void throwsForVoidMultipleParams() {
            assertThatThrownBy(() -> service.processVoid("x", 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ScopedService.processVoid");
        }

        @Test
        @DisplayName("does not call the method body")
        void doesNotCallMethodBody() {
            assertThatThrownBy(() -> service.process())
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
