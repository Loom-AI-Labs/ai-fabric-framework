package ai.fabric.indexing.observability;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.repository.IndexingQueueRepository;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sanitized runtime view of resolved entity contracts and queue readiness.
 */
@Endpoint(id = "aifabricEntities")
public class AIEntityIndexingEndpoint {

    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final IndexingQueueRepository queueRepository;
    private final ListableBeanFactory beanFactory;

    public AIEntityIndexingEndpoint(
        AIEntityDescriptorRegistry descriptorRegistry,
        IndexingQueueRepository queueRepository,
        ListableBeanFactory beanFactory
    ) {
        this.descriptorRegistry = descriptorRegistry;
        this.queueRepository = queueRepository;
        this.beanFactory = beanFactory;
    }

    @ReadOperation
    public Map<String, Object> entities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            "entities",
            descriptorRegistry.descriptors().stream()
                .sorted(Comparator.comparing(AIEntityDescriptor::entityType))
                .map(this::entity)
                .toList()
        );
        result.put("processMethods", processMethods());
        result.put("queue", Map.of(
            "ready", true,
            "commitPending", queueRepository.countByStatus(
                IndexingStatus.COMMIT_PENDING
            ),
            "pending", queueRepository.countByStatus(IndexingStatus.PENDING),
            "processing", queueRepository.countByStatus(IndexingStatus.PROCESSING),
            "deadLetters", queueRepository.countByStatus(IndexingStatus.DEAD_LETTER)
        ));
        return result;
    }

    private Map<String, Object> entity(AIEntityDescriptor descriptor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityType", descriptor.entityType());
        result.put("class", descriptor.entityClass().getName());
        result.put("identitySource", descriptor.identitySource());
        result.put("indexingEnabled", descriptor.indexingEnabled());
        result.put("projectionHash", descriptor.projectionHash());
        result.put("projectionMaxCharacters", descriptor.projectionMaxCharacters());
        result.put("capabilities", descriptor.effectiveCapabilities());
        result.put("configurationSources", descriptor.configurationSources());
        result.put("strategies", Map.of(
            "create", descriptor.strategyFor(ai.fabric.indexing.api.AIProcessOperation.CREATE),
            "update", descriptor.strategyFor(ai.fabric.indexing.api.AIProcessOperation.UPDATE),
            "delete", descriptor.strategyFor(ai.fabric.indexing.api.AIProcessOperation.DELETE)
        ));
        result.put(
            "searchableFields",
            descriptor.searchableFields().stream()
                .map(field -> Map.of(
                    "name", field.name(),
                    "destinations", field.destinations(),
                    "priority", field.priority(),
                    "required", field.required()
                ))
                .toList()
        );
        result.put(
            "contextFields",
            descriptor.contextFields().stream()
                .map(field -> Map.of(
                    "name", field.key(),
                    "destinations", field.destinations(),
                    "priority", field.priority(),
                    "required", field.required(),
                    "sanitizePII", field.sanitizePII()
                ))
                .toList()
        );
        return result;
    }

    private List<Map<String, Object>> processMethods() {
        return Arrays.stream(beanFactory.getBeanDefinitionNames())
            .map(this::processMethods)
            .flatMap(List::stream)
            .sorted(Comparator.comparing(method -> String.valueOf(method.get("method"))))
            .toList();
    }

    private List<Map<String, Object>> processMethods(String beanName) {
        Class<?> type;
        try {
            type = beanFactory.getType(beanName, false);
        } catch (RuntimeException exception) {
            return List.of();
        }
        if (type == null) {
            return List.of();
        }
        Class<?> targetType = ClassUtils.getUserClass(type);
        return Arrays.stream(targetType.getMethods())
            .map(method -> processMethod(targetType, method))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private Map<String, Object> processMethod(Class<?> type, Method method) {
        AIProcess annotation = AnnotatedElementUtils.findMergedAnnotation(
            method,
            AIProcess.class
        );
        if (annotation == null) {
            return null;
        }
        return Map.of(
            "beanClass", type.getName(),
            "method", method.getName(),
            "operation", annotation.operation(),
            "entityTypeAssertion", annotation.entityType(),
            "strategy", annotation.indexingStrategy(),
            "targetResolver", annotation.targetResolver().getName()
        );
    }
}
