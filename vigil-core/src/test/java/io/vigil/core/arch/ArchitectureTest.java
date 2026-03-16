package io.vigil.core.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    @Test
    void vigilCoreMustNotImportSpring() {
        JavaClasses core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.vigil.core");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .as("vigil-core must have zero Spring dependencies (D07)");

        rule.check(core);
    }

    @Test
    void vigilLockJdbcMustNotImportVigilLockRedis() {
        JavaClasses jdbcClasses = new ClassFileImporter()
                .importPackages("io.vigil.lock.jdbc");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("io.vigil.lock.redis..")
                .as("vigil-lock-jdbc must not depend on vigil-lock-redis");

        rule.check(jdbcClasses);
    }

    @Test
    void vigilLockRedisMustNotImportVigilLockJdbc() {
        JavaClasses redisClasses = new ClassFileImporter()
                .importPackages("io.vigil.lock.redis");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("io.vigil.lock.jdbc..")
                .as("vigil-lock-redis must not depend on vigil-lock-jdbc");

        rule.check(redisClasses);
    }

    @Test
    void vigilSchedulerMustNotImportVigilActuator() {
        JavaClasses schedulerClasses = new ClassFileImporter()
                .importPackages("io.vigil.scheduler");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("io.vigil.actuator..")
                .as("vigil-scheduler must not depend on vigil-actuator");

        rule.check(schedulerClasses);
    }
}
