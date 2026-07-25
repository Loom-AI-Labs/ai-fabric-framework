package ai.fabric.aspect;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIProcess;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.api.AIProcessContractException;
import ai.fabric.indexing.api.NoAIProcessTargetResolver;
import ai.fabric.indexing.descriptor.AIEntityDescriptorInitializer;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * Resolves every lifecycle method contract at startup so invalid targets never
 * wait for a production request to fail.
 */
public class AIEntityContractValidator implements SmartInitializingSingleton {

    private final ListableBeanFactory beanFactory;
    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final AIEntityDescriptorInitializer descriptorInitializer;
    private final AIEntityConfigurationLoader configurationLoader;
    private final AIConfiguredEntityProjectionService configuredProjectionService;

    public AIEntityContractValidator(
        ListableBeanFactory beanFactory,
        AIEntityDescriptorRegistry descriptorRegistry,
        AIEntityDescriptorInitializer descriptorInitializer,
        AIEntityConfigurationLoader configurationLoader,
        AIConfiguredEntityProjectionService configuredProjectionService
    ) {
        this.beanFactory = beanFactory;
        this.descriptorRegistry = descriptorRegistry;
        this.descriptorInitializer = descriptorInitializer;
        this.configurationLoader = configurationLoader;
        this.configuredProjectionService = configuredProjectionService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        descriptorInitializer.initialize();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> targetClass = targetClass(beanName);
            if (targetClass == null) {
                continue;
            }
            Arrays.stream(targetClass.getMethods())
                .filter(method -> AnnotatedElementUtils.findMergedAnnotation(
                    method,
                    AIProcess.class
                ) != null)
                .forEach(method -> validateMethod(targetClass, method));
        }
        configurationLoader.getEntityConfigs().forEach((entityType, config) -> {
            if (!descriptorRegistry.hasEntityType(entityType)) {
                configuredProjectionService.validateConfiguration(config);
            }
        });
    }

    void validateMethod(Class<?> targetClass, Method method) {
        AIProcess annotation = AnnotatedElementUtils.findMergedAnnotation(
            method,
            AIProcess.class
        );
        if (annotation == null) {
            return;
        }

        if (annotation.targetResolver() != NoAIProcessTargetResolver.class) {
            String[] resolverBeans = beanFactory.getBeanNamesForType(
                annotation.targetResolver(),
                true,
                false
            );
            if (resolverBeans.length != 1) {
                throw new AIProcessContractException(
                    "@AIProcess target resolver %s must have exactly one Spring bean; found %d for %s"
                        .formatted(
                            annotation.targetResolver().getName(),
                            resolverBeans.length,
                            method.toGenericString()
                        )
                );
            }
            if (StringUtils.hasText(annotation.entityType())) {
                descriptorRegistry.getByEntityType(annotation.entityType().trim());
            }
            return;
        }

        Class<?> entityClass = defaultResultEntityClass(targetClass, method);
        if (entityClass == null
            || AnnotatedElementUtils.findMergedAnnotation(
                entityClass,
                AICapable.class
            ) == null) {
            throw new AIProcessContractException(
                "@AIProcess method %s must return an @AICapable entity, Optional, collection, "
                    + "or array, or declare a targetResolver"
                    .formatted(method.toGenericString())
            );
        }

        AIEntityDescriptor descriptor = descriptorRegistry.resolve(entityClass);
        if (StringUtils.hasText(annotation.entityType())
            && !annotation.entityType().trim().equals(descriptor.entityType())) {
            throw new AIProcessContractException(
                "@AIProcess entityType '%s' does not match target entityType '%s' on %s"
                    .formatted(
                        annotation.entityType(),
                        descriptor.entityType(),
                        method.toGenericString()
                    )
            );
        }
        descriptor.strategyFor(annotation.operation());
    }

    private Class<?> defaultResultEntityClass(
        Class<?> targetClass,
        Method method
    ) {
        ResolvableType returnType = ResolvableType.forMethodReturnType(
            method,
            targetClass
        );
        Class<?> raw = returnType.resolve();
        if (raw == null || raw == Void.TYPE || raw == Void.class) {
            return null;
        }
        if (raw.isArray()) {
            return raw.getComponentType();
        }
        if (Optional.class.isAssignableFrom(raw)
            || Collection.class.isAssignableFrom(raw)) {
            return returnType.getGeneric(0).resolve();
        }
        return raw;
    }

    private Class<?> targetClass(String beanName) {
        try {
            Class<?> type = beanFactory.getType(beanName, false);
            return type == null ? null : ClassUtils.getUserClass(type);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
