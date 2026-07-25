package ai.fabric.aspect;

import ai.fabric.annotation.AICapable;
import ai.fabric.indexing.api.AIProcessContractException;
import ai.fabric.indexing.api.AIProcessInvocation;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AIProcessTarget;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

final class DefaultAIProcessTargetResolver {

    Collection<AIProcessTarget> resolve(AIProcessInvocation invocation) {
        List<Object> values = flatten(invocation.result());
        if (values.isEmpty()) {
            throw new AIProcessContractException(
                "Default @AIProcess target resolution found no result entity for "
                    + invocation.method().toGenericString()
            );
        }

        List<AIProcessTarget> targets = new ArrayList<>(values.size());
        for (Object value : values) {
            Class<?> entityClass = applicationClass(value.getClass());
            if (AnnotatedElementUtils.findMergedAnnotation(
                entityClass,
                AICapable.class
            ) == null) {
                throw new AIProcessContractException(
                    "Default @AIProcess target is not @AICapable: "
                        + entityClass.getName()
                );
            }
            targets.add(
                invocation.operation() == AIProcessOperation.DELETE
                    ? AIProcessTarget.delete(entityClass, value)
                    : AIProcessTarget.upsert(entityClass, value)
            );
        }
        return List.copyOf(targets);
    }

    private List<Object> flatten(Object result) {
        if (result == null) {
            return List.of();
        }
        if (result instanceof Optional<?> optional) {
            return optional
                .<List<Object>>map(value -> List.of(value))
                .orElseGet(List::of);
        }
        if (result instanceof Collection<?> collection) {
            return collection.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> (Object) value)
                .toList();
        }
        if (result.getClass().isArray()) {
            List<Object> values = new ArrayList<>(Array.getLength(result));
            for (int index = 0; index < Array.getLength(result); index++) {
                Object value = Array.get(result, index);
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        }
        return List.of(result);
    }

    private Class<?> applicationClass(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (AnnotatedElementUtils.findMergedAnnotation(
                current,
                AICapable.class
            ) != null) {
                return current;
            }
            current = current.getSuperclass();
        }
        return type;
    }
}
