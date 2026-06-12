package io.vigil.scheduler.advisor;

import io.vigil.scheduler.ExactlyOnce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Component
public class ExactlyOnceStartupValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ExactlyOnceStartupValidator.class);

    private final ApplicationContext context;
    private final Environment        environment;

    public ExactlyOnceStartupValidator(ApplicationContext context, Environment environment) {
        this.context     = context;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate().forEach(log::warn);
    }

    public List<String> validate() {
        boolean virtualThreads = environment.getProperty(
                "spring.threads.virtual.enabled", Boolean.class, false);

        List<String> warnings = new ArrayList<>();

        for (String beanName : context.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = context.getBean(beanName);
            } catch (Exception ignored) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getMethods()) {
                if (method.isAnnotationPresent(ExactlyOnce.class)) {
                    String label = targetClass.getSimpleName() + "." + method.getName();
                    warnings.add("[Vigil] @ExactlyOnce on '" + label + "': guarantee is cooperative" +
                            " - provider must honor Idempotency-Key header." +
                            " If the provider ignores it, Vigil provides at-least-once, not exactly-once.");
                    if (virtualThreads) {
                        warnings.add("[Vigil] @ExactlyOnce on '" + label + "':" +
                                " spring.threads.virtual.enabled=true" +
                                " - ThreadLocal does not propagate across virtual thread boundaries." +
                                " Async calls inside this method will not carry the idempotency key.");
                    }
                }
            }
        }
        return warnings;
    }
}
