package ai.fabric.aspect;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessContractException;
import ai.fabric.indexing.api.AIProcessInvocation;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AIProcessTarget;
import ai.fabric.indexing.api.AIProcessTargetResolver;
import ai.fabric.indexing.api.NoAIProcessTargetResolver;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Enqueues explicit entity lifecycle work inside the source transaction.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class AIProcessAspect {

    private final AIEntityIndexingGateway indexingGateway;
    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final ListableBeanFactory beanFactory;
    private final DefaultAIProcessTargetResolver defaultResolver =
        new DefaultAIProcessTargetResolver();

    public AIProcessAspect(
        AIEntityIndexingGateway indexingGateway,
        AIEntityDescriptorRegistry descriptorRegistry,
        ListableBeanFactory beanFactory
    ) {
        this.indexingGateway = Objects.requireNonNull(indexingGateway);
        this.descriptorRegistry = Objects.requireNonNull(descriptorRegistry);
        this.beanFactory = Objects.requireNonNull(beanFactory);
    }

    @Around("@annotation(aiProcess)")
    public Object process(
        ProceedingJoinPoint joinPoint,
        AIProcess aiProcess
    ) throws Throwable {
        Object result = joinPoint.proceed();

        AIProcessInvocation invocation = new AIProcessInvocation(
            method(joinPoint),
            joinPoint.getTarget(),
            Arrays.asList(joinPoint.getArgs()),
            result,
            aiProcess.operation(),
            aiProcess.entityType()
        );
        if (indexingDisabled(aiProcess, invocation)) {
            return result;
        }
        Collection<AIProcessTarget> targets = resolveTargets(aiProcess, invocation);
        if (targets == null || targets.isEmpty()) {
            throw new AIProcessContractException(
                "@AIProcess target resolver returned no targets for "
                    + invocation.method().toGenericString()
            );
        }
        for (AIProcessTarget target : targets) {
            if (target == null) {
                throw new AIProcessContractException(
                    "@AIProcess target resolver returned a null target for "
                        + invocation.method().toGenericString()
                );
            }
            dispatch(aiProcess, target);
        }
        return result;
    }

    private Collection<AIProcessTarget> resolveTargets(
        AIProcess annotation,
        AIProcessInvocation invocation
    ) {
        if (annotation.targetResolver() == NoAIProcessTargetResolver.class) {
            return defaultResolver.resolve(invocation);
        }
        AIProcessTargetResolver resolver = beanFactory
            .getBeanProvider(annotation.targetResolver())
            .getIfAvailable();
        if (resolver == null) {
            throw new AIProcessContractException(
                "No bean exists for @AIProcess target resolver "
                    + annotation.targetResolver().getName()
            );
        }
        return resolver.resolve(invocation);
    }

    private void dispatch(AIProcess annotation, AIProcessTarget target) {
        if (target.entity() != null
            && !target.entityClass().isAssignableFrom(target.entity().getClass())) {
            throw new AIProcessContractException(
                "Resolved entity snapshot type %s does not match declared target class %s"
                    .formatted(
                        target.entity().getClass().getName(),
                        target.entityClass().getName()
                    )
            );
        }
        AIEntityDescriptor descriptor = descriptorRegistry.resolve(target.entityClass());
        assertEntityType(annotation.entityType(), descriptor);
        if (!descriptor.indexingEnabled()) {
            return;
        }
        if (annotation.operation() == AIProcessOperation.DELETE) {
            if (target.entity() != null) {
                indexingGateway.delete(target.entity(), annotation.indexingStrategy());
            } else {
                indexingGateway.delete(
                    target.entityClass(),
                    target.entityId(),
                    annotation.indexingStrategy()
                );
            }
            return;
        }
        if (target.entity() == null) {
            throw new AIProcessContractException(
                annotation.operation() + " requires an entity snapshot for "
                    + descriptor.entityType()
            );
        }
        indexingGateway.upsert(
            target.entity(),
            annotation.operation(),
            annotation.indexingStrategy()
        );
    }

    private void assertEntityType(
        String declaredEntityType,
        AIEntityDescriptor descriptor
    ) {
        if (StringUtils.hasText(declaredEntityType)
            && !declaredEntityType.trim().equals(descriptor.entityType())) {
            throw new AIProcessContractException(
                "@AIProcess entityType '%s' does not match target entityType '%s'"
                    .formatted(declaredEntityType, descriptor.entityType())
            );
        }
    }

    private boolean indexingDisabled(
        AIProcess annotation,
        AIProcessInvocation invocation
    ) {
        if (StringUtils.hasText(annotation.entityType())) {
            String entityType = annotation.entityType().trim();
            return descriptorRegistry.hasEntityType(entityType)
                && !descriptorRegistry.getByEntityType(entityType).indexingEnabled();
        }
        if (annotation.targetResolver() != NoAIProcessTargetResolver.class) {
            return false;
        }

        ResolvableType returnType = ResolvableType.forMethodReturnType(
            invocation.method(),
            invocation.service().getClass()
        );
        Class<?> raw = returnType.resolve();
        Class<?> entityClass;
        if (raw == null || raw == Void.TYPE || raw == Void.class) {
            return false;
        } else if (raw.isArray()) {
            entityClass = raw.getComponentType();
        } else if (java.util.Optional.class.isAssignableFrom(raw)
            || Collection.class.isAssignableFrom(raw)) {
            entityClass = returnType.getGeneric(0).resolve();
        } else {
            entityClass = raw;
        }
        return entityClass != null
            && AnnotatedElementUtils.findMergedAnnotation(
                entityClass,
                ai.fabric.annotation.AICapable.class
            ) != null
            && !descriptorRegistry.resolve(entityClass).indexingEnabled();
    }

    private Method method(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        try {
            return joinPoint.getTarget()
                .getClass()
                .getMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException ignored) {
            return method;
        }
    }
}
