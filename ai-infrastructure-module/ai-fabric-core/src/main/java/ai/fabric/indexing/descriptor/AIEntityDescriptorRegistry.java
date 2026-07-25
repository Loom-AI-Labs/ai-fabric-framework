package ai.fabric.indexing.descriptor;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.annotation.NoMigrationRepository;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityAnalysisPolicy;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.dto.AISearchableField;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AIEntityDescriptorContributor;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.api.EntityIdentityResolver;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIAnalysisPolicy;
import ai.fabric.indexing.model.AIContextFieldDescriptor;
import ai.fabric.indexing.model.AIEntityCapability;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.model.AISearchFieldDescriptor;
import ai.fabric.indexing.model.AIValueAccessor;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.temporal.TemporalAccessor;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles and caches the canonical entity contract.
 */
public class AIEntityDescriptorRegistry {

    public static final int DEFAULT_PROJECTION_MAX_CHARACTERS = 8_000;

    private final AIEntityConfigurationLoader configurationLoader;
    private final List<EntityIdentityResolver> applicationIdentityResolvers;
    private final List<AIEntityDescriptorContributor> contributors;
    private final ObjectProvider<PIIDetectionService> piiDetectionServiceProvider;
    private final ObjectMapper canonicalObjectMapper;
    private final Map<Class<?>, AIEntityDescriptor> descriptorsByClass = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> classesByEntityType = new ConcurrentHashMap<>();

    public AIEntityDescriptorRegistry(
        AIEntityConfigurationLoader configurationLoader,
        List<EntityIdentityResolver> applicationIdentityResolvers,
        List<AIEntityDescriptorContributor> contributors,
        ObjectProvider<PIIDetectionService> piiDetectionServiceProvider,
        ObjectMapper objectMapper
    ) {
        this.configurationLoader = Objects.requireNonNull(configurationLoader);
        this.applicationIdentityResolvers = applicationIdentityResolvers == null
            ? List.of()
            : List.copyOf(applicationIdentityResolvers);
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
        this.piiDetectionServiceProvider = piiDetectionServiceProvider;
        this.canonicalObjectMapper = objectMapper.copy()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public AIEntityDescriptor resolve(Object entity) {
        Objects.requireNonNull(entity, "entity is required");
        return resolve(resolveApplicationClass(entity.getClass()));
    }

    public AIEntityDescriptor resolve(Class<?> requestedClass) {
        Class<?> entityClass = resolveApplicationClass(requestedClass);
        return descriptorsByClass.computeIfAbsent(entityClass, this::compile);
    }

    public AIEntityDescriptor getByEntityType(String entityType) {
        if (!StringUtils.hasText(entityType)) {
            throw new AIEntityContractException("entityType is required");
        }
        Class<?> entityClass = classesByEntityType.get(entityType.trim());
        if (entityClass == null) {
            throw new AIEntityContractException("No annotated entity registered for " + entityType.trim());
        }
        return resolve(entityClass);
    }

    public Collection<AIEntityDescriptor> descriptors() {
        return List.copyOf(descriptorsByClass.values());
    }

    public boolean hasEntityType(String entityType) {
        return StringUtils.hasText(entityType)
            && classesByEntityType.containsKey(entityType.trim());
    }

    public void compileContributors() {
        contributors.stream()
            .map(AIEntityDescriptorContributor::entityClasses)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .filter(Objects::nonNull)
            .forEach(this::resolve);
    }

    private AIEntityDescriptor compile(Class<?> entityClass) {
        AICapable capable = AnnotatedElementUtils.findMergedAnnotation(entityClass, AICapable.class);
        if (capable == null) {
            throw new AIEntityContractException(
                "Class %s is not annotated with @AICapable".formatted(entityClass.getName())
            );
        }

        String entityType = requireText(capable.entityType(), "@AICapable.entityType");
        Class<?> existing = classesByEntityType.putIfAbsent(entityType, entityClass);
        if (existing != null && !existing.equals(entityClass)) {
            throw new AIEntityContractException(
                "Duplicate entityType '%s' on %s and %s"
                    .formatted(entityType, existing.getName(), entityClass.getName())
            );
        }

        AIEntityConfig config = configurationLoader.getEntityConfig(entityType);
        List<AISearchFieldDescriptor> searchFields = compileSearchFields(entityClass, config);
        List<AIContextFieldDescriptor> contextFields = compileContextFields(entityClass, config);
        EntityIdentityResolver identityResolver = compileIdentityResolver(entityClass);

        boolean hasSemanticProjection = searchFields.stream()
            .anyMatch(field -> field.destinations().contains(AISearchDestination.SEMANTIC_SEARCH));
        Boolean configuredEnabled = config != null && config.getIndexing() != null
            ? config.getIndexing().getEnabled()
            : null;
        boolean indexingEnabled = configuredEnabled != null ? configuredEnabled : hasSemanticProjection;
        if (indexingEnabled && !hasSemanticProjection) {
            throw new AIEntityContractException(
                "Indexing is enabled for %s but no SEMANTIC_SEARCH field is declared"
                    .formatted(entityType)
            );
        }

        int projectionMaxCharacters = resolveProjectionMaxCharacters(config);
        AIAnalysisPolicy analysisPolicy = resolveAnalysisPolicy(config);
        if (analysisPolicy.enabled() && !indexingEnabled) {
            throw new AIEntityContractException(
                "Analysis cannot be enabled for non-indexed entity " + entityType
            );
        }

        Set<AIEntityCapability> capabilities = deriveCapabilities(
            capable,
            searchFields,
            contextFields,
            indexingEnabled,
            analysisPolicy
        );

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("entityType", "annotation");
        sources.put("fields", "annotation");
        sources.put("indexing.enabled", configuredEnabled == null ? "derived" : "config-data");
        sources.put("analysis", config == null ? "default" : "config-data");

        String projectionHash = projectionHash(
            entityClass,
            entityType,
            searchFields,
            contextFields,
            indexingEnabled,
            projectionMaxCharacters,
            analysisPolicy
        );

        return new AIEntityDescriptor(
            entityClass,
            entityType,
            identityResolver,
            identityResolver.source(),
            searchFields,
            contextFields,
            indexingEnabled,
            projectionMaxCharacters,
            analysisPolicy,
            requireConcreteDefaultStrategy(capable.indexingStrategy(), entityType),
            capable.onCreateStrategy(),
            capable.onUpdateStrategy(),
            capable.onDeleteStrategy(),
            capable.migrationRepository(),
            projectionHash,
            capabilities,
            sources
        );
    }

    private List<AISearchFieldDescriptor> compileSearchFields(
        Class<?> entityClass,
        AIEntityConfig config
    ) {
        List<AIValueAccessor> accessors = annotatedAccessors(entityClass, AISearchable.class);
        Map<String, AISearchableField> overrides = searchableOverrides(config);
        List<AISearchFieldDescriptor> descriptors = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        for (int index = 0; index < accessors.size(); index++) {
            AIValueAccessor accessor = accessors.get(index);
            AISearchable annotation = annotation(accessor, AISearchable.class);
            String name = StringUtils.hasText(annotation.name())
                ? annotation.name().trim()
                : accessor.memberName();
            if (!names.add(name)) {
                throw new AIEntityContractException(
                    "Duplicate searchable field name '%s' on %s".formatted(name, entityClass.getName())
                );
            }

            Set<AISearchDestination> destinations = enumSet(annotation.destinations());
            AISearchPreprocessing preprocessing = annotation.preprocessing();
            int maxLength = annotation.maxLength();
            int priority = annotation.priority();
            boolean required = annotation.required();

            AISearchableField override = overrides.remove(name);
            if (override != null) {
                Set<AISearchDestination> requested = override.getDestinations();
                if (requested != null && !requested.isEmpty()) {
                    if (!destinations.containsAll(requested)) {
                        throw new AIEntityContractException(
                            "Config cannot widen searchable destinations for %s.%s"
                                .formatted(entityClass.getName(), name)
                        );
                    }
                    destinations = EnumSet.copyOf(requested);
                }
                if (override.getPreprocessing() != null) {
                    preprocessing = tightenPreprocessing(preprocessing, override.getPreprocessing(), name);
                }
                if (override.getMaxLength() != null) {
                    if (maxLength > 0 && override.getMaxLength() > maxLength) {
                        throw new AIEntityContractException(
                            "Config cannot increase maxLength for %s.%s"
                                .formatted(entityClass.getName(), name)
                        );
                    }
                    maxLength = override.getMaxLength();
                }
                if (override.getPriority() != null) {
                    priority = override.getPriority();
                }
                if (Boolean.TRUE.equals(override.getRequired())) {
                    required = true;
                }
            }

            validateSearchField(
                entityClass,
                name,
                destinations,
                preprocessing,
                maxLength,
                priority
            );
            descriptors.add(new AISearchFieldDescriptor(
                accessor,
                name,
                destinations,
                preprocessing,
                maxLength,
                priority,
                required,
                index
            ));
        }

        if (!overrides.isEmpty()) {
            throw new AIEntityContractException(
                "Config references non-annotated searchable fields %s on %s"
                    .formatted(overrides.keySet(), entityClass.getName())
            );
        }

        return descriptors.stream()
            .sorted(fieldOrder())
            .toList();
    }

    private List<AIContextFieldDescriptor> compileContextFields(
        Class<?> entityClass,
        AIEntityConfig config
    ) {
        List<AIValueAccessor> accessors = annotatedAccessors(entityClass, AIContext.class);
        Map<String, AIMetadataField> overrides = contextOverrides(config);
        List<AIContextFieldDescriptor> descriptors = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();

        for (int index = 0; index < accessors.size(); index++) {
            AIValueAccessor accessor = accessors.get(index);
            AIContext annotation = annotation(accessor, AIContext.class);
            String key = StringUtils.hasText(annotation.key())
                ? annotation.key().trim()
                : accessor.memberName();
            if (!keys.add(key)) {
                throw new AIEntityContractException(
                    "Duplicate context key '%s' on %s".formatted(key, entityClass.getName())
                );
            }

            AIContextDataType dataType = annotation.dataType();
            Set<AIContextDestination> destinations = enumSet(annotation.destinations());
            String format = annotation.format();
            String description = annotation.description();
            int priority = annotation.priority();
            boolean required = annotation.required();
            boolean sanitizePII = annotation.sanitizePII();

            AIMetadataField override = overrides.remove(key);
            if (override != null) {
                if (override.getDataType() != null) {
                    if (dataType != AIContextDataType.AUTO
                        && override.getDataType() != dataType) {
                        throw new AIEntityContractException(
                            "Config cannot replace annotated dataType for %s.%s"
                                .formatted(entityClass.getName(), key)
                        );
                    }
                    dataType = override.getDataType();
                }
                Set<AIContextDestination> requested = override.getDestinations();
                if (requested != null && !requested.isEmpty()) {
                    if (!destinations.containsAll(requested)) {
                        throw new AIEntityContractException(
                            "Config cannot widen context destinations for %s.%s"
                                .formatted(entityClass.getName(), key)
                        );
                    }
                    destinations = EnumSet.copyOf(requested);
                }
                if (StringUtils.hasText(override.getFormat())) {
                    format = override.getFormat();
                }
                if (StringUtils.hasText(override.getDescription())) {
                    description = override.getDescription();
                }
                if (override.getPriority() != null) {
                    priority = override.getPriority();
                }
                if (Boolean.TRUE.equals(override.getRequired())) {
                    required = true;
                }
                if (Boolean.TRUE.equals(override.getSanitizePII())) {
                    sanitizePII = true;
                }
            }

            validateContextField(
                entityClass,
                accessor,
                key,
                dataType,
                format,
                destinations,
                description,
                priority,
                sanitizePII
            );
            descriptors.add(new AIContextFieldDescriptor(
                accessor,
                key,
                dataType,
                format,
                destinations,
                description,
                priority,
                required,
                sanitizePII,
                index
            ));
        }

        if (!overrides.isEmpty()) {
            throw new AIEntityContractException(
                "Config references non-annotated context fields %s on %s"
                    .formatted(overrides.keySet(), entityClass.getName())
            );
        }

        return descriptors.stream()
            .sorted(contextOrder())
            .toList();
    }

    private EntityIdentityResolver compileIdentityResolver(Class<?> entityClass) {
        List<EntityIdentityResolver> custom = applicationIdentityResolvers.stream()
            .filter(resolver -> resolver.supports(entityClass))
            .toList();
        if (custom.size() > 1) {
            throw new AIEntityContractException(
                "Multiple EntityIdentityResolver beans support " + entityClass.getName()
            );
        }
        if (custom.size() == 1) {
            return new CanonicalizingIdentityResolver(custom.getFirst(), canonicalObjectMapper);
        }

        List<AIValueAccessor> explicit = annotatedAccessors(entityClass, AIIdentity.class);
        if (explicit.size() > 1) {
            throw new AIEntityContractException(
                "Multiple @AIIdentity members declared on " + entityClass.getName()
            );
        }
        if (explicit.size() == 1) {
            return new MemberIdentityResolver(
                entityClass,
                explicit.getFirst(),
                "@AIIdentity:" + explicit.getFirst().memberName(),
                canonicalObjectMapper
            );
        }

        List<AIValueAccessor> jpa = new ArrayList<>();
        jpa.addAll(annotatedAccessors(entityClass, Id.class));
        jpa.addAll(annotatedAccessors(entityClass, EmbeddedId.class));
        jpa = jpa.stream()
            .collect(
                LinkedHashMap<String, AIValueAccessor>::new,
                (map, accessor) -> map.putIfAbsent(accessor.memberName(), accessor),
                Map::putAll
            )
            .values()
            .stream()
            .toList();

        if (jpa.size() != 1) {
            throw new AIEntityContractException(
                jpa.isEmpty()
                    ? "No identity source declared for " + entityClass.getName()
                    : "Multiple JPA identity members declared on " + entityClass.getName()
            );
        }
        return new MemberIdentityResolver(
            entityClass,
            jpa.getFirst(),
            "JPA:" + jpa.getFirst().memberName(),
            canonicalObjectMapper
        );
    }

    private List<AIValueAccessor> annotatedAccessors(
        Class<?> entityClass,
        Class<? extends Annotation> annotationType
    ) {
        List<AIValueAccessor> result = new ArrayList<>();
        List<Class<?>> hierarchy = hierarchy(entityClass);
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && field.isAnnotationPresent(annotationType)) {
                    result.add(new FieldValueAccessor(field));
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isSynthetic()
                    && !method.isBridge()
                    && method.isAnnotationPresent(annotationType)) {
                    if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) {
                        throw new AIEntityContractException(
                            "Annotated accessor must be a zero-argument value method: " + method
                        );
                    }
                    result.add(new MethodValueAccessor(method));
                }
            }
        }
        return result;
    }

    private List<Class<?>> hierarchy(Class<?> entityClass) {
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            hierarchy.addFirst(current);
            current = current.getSuperclass();
        }
        return hierarchy;
    }

    private Class<?> resolveApplicationClass(Class<?> requestedClass) {
        Objects.requireNonNull(requestedClass, "entityClass is required");
        Class<?> current = requestedClass;
        while (current != null && current != Object.class) {
            if (AnnotatedElementUtils.findMergedAnnotation(current, AICapable.class) != null) {
                return current;
            }
            current = current.getSuperclass();
        }
        return requestedClass;
    }

    private Map<String, AISearchableField> searchableOverrides(AIEntityConfig config) {
        Map<String, AISearchableField> result = new LinkedHashMap<>();
        if (config == null || config.getSearchableFields() == null) {
            return result;
        }
        for (AISearchableField field : config.getSearchableFields()) {
            String name = requireText(field.getName(), "searchable field name");
            if (result.putIfAbsent(name, field) != null) {
                throw new AIEntityContractException("Duplicate searchable config field " + name);
            }
        }
        return result;
    }

    private Map<String, AIMetadataField> contextOverrides(AIEntityConfig config) {
        Map<String, AIMetadataField> result = new LinkedHashMap<>();
        if (config == null || config.getMetadataFields() == null) {
            return result;
        }
        for (AIMetadataField field : config.getMetadataFields()) {
            String name = requireText(field.getName(), "metadata field name");
            if (result.putIfAbsent(name, field) != null) {
                throw new AIEntityContractException("Duplicate metadata config field " + name);
            }
        }
        return result;
    }

    private int resolveProjectionMaxCharacters(AIEntityConfig config) {
        AIEntityIndexingPolicy policy = config == null ? null : config.getIndexing();
        Integer configured = policy == null ? null : policy.getMaxCharacters();
        int value = configured == null ? DEFAULT_PROJECTION_MAX_CHARACTERS : configured;
        if (value < 1 || value > DEFAULT_PROJECTION_MAX_CHARACTERS) {
            throw new AIEntityContractException(
                "projection maxCharacters must be between 1 and "
                    + DEFAULT_PROJECTION_MAX_CHARACTERS
            );
        }
        return value;
    }

    private AIAnalysisPolicy resolveAnalysisPolicy(AIEntityConfig config) {
        AIEntityAnalysisPolicy policy = config == null ? null : config.getAnalysis();
        if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())) {
            return AIAnalysisPolicy.disabled();
        }
        Set<AIProcessOperation> after = policy.getAfter() == null
            ? Set.of()
            : Set.copyOf(policy.getAfter());
        if (after.isEmpty()) {
            throw new AIEntityContractException("analysis.after is required when analysis is enabled");
        }
        return new AIAnalysisPolicy(true, after);
    }

    private Set<AIEntityCapability> deriveCapabilities(
        AICapable capable,
        List<AISearchFieldDescriptor> searchFields,
        List<AIContextFieldDescriptor> contextFields,
        boolean indexingEnabled,
        AIAnalysisPolicy analysisPolicy
    ) {
        EnumSet<AIEntityCapability> capabilities = EnumSet.noneOf(AIEntityCapability.class);
        if (indexingEnabled) {
            capabilities.add(AIEntityCapability.INDEXING);
        }
        searchFields.forEach(field -> {
            if (field.destinations().contains(AISearchDestination.SEMANTIC_SEARCH)) {
                capabilities.add(AIEntityCapability.SEMANTIC_SEARCH);
            }
            if (field.destinations().contains(AISearchDestination.RAG_CONTEXT)) {
                capabilities.add(AIEntityCapability.RAG_CONTEXT);
            }
        });
        contextFields.forEach(field -> field.destinations().forEach(destination -> {
            switch (destination) {
                case VECTOR_METADATA -> capabilities.add(AIEntityCapability.VECTOR_METADATA);
                case LLM_CONTEXT -> capabilities.add(AIEntityCapability.LLM_CONTEXT);
                case API_RESPONSE -> capabilities.add(AIEntityCapability.API_RESPONSE);
            }
        }));
        if (analysisPolicy.enabled()) {
            capabilities.add(AIEntityCapability.ANALYSIS);
        }
        if (capable.migrationRepository() != NoMigrationRepository.class) {
            capabilities.add(AIEntityCapability.MIGRATION);
        }
        return capabilities;
    }

    private String projectionHash(
        Class<?> entityClass,
        String entityType,
        List<AISearchFieldDescriptor> searchFields,
        List<AIContextFieldDescriptor> contextFields,
        boolean indexingEnabled,
        int maxCharacters,
        AIAnalysisPolicy analysisPolicy
    ) {
        StringBuilder source = new StringBuilder()
            .append(entityClass.getName()).append('|')
            .append(entityType).append('|')
            .append(indexingEnabled).append('|')
            .append(maxCharacters).append('|')
            .append(analysisPolicy.enabled()).append('|')
            .append(analysisPolicy.after());
        searchFields.forEach(field -> source
            .append("|S:")
            .append(field.name()).append(':')
            .append(field.destinations()).append(':')
            .append(field.preprocessing()).append(':')
            .append(field.maxLength()).append(':')
            .append(field.priority()).append(':')
            .append(field.required()));
        contextFields.forEach(field -> source
            .append("|C:")
            .append(field.key()).append(':')
            .append(field.dataType()).append(':')
            .append(field.destinations()).append(':')
            .append(field.format()).append(':')
            .append(field.priority()).append(':')
            .append(field.required()).append(':')
            .append(field.sanitizePII()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new AIEntityContractException("Unable to hash entity descriptor", exception);
        }
    }

    private void validateSearchField(
        Class<?> entityClass,
        String name,
        Set<AISearchDestination> destinations,
        AISearchPreprocessing preprocessing,
        int maxLength,
        int priority
    ) {
        if (destinations.isEmpty()) {
            throw new AIEntityContractException(
                "Searchable field %s.%s has no destination".formatted(entityClass.getName(), name)
            );
        }
        if (maxLength == 0 || maxLength < -1) {
            throw new AIEntityContractException("maxLength must be -1 or positive for " + name);
        }
        validatePriority(priority, name);
        if (preprocessing == AISearchPreprocessing.SANITIZE
            && (piiDetectionServiceProvider == null
                || piiDetectionServiceProvider.getIfAvailable() == null)) {
            throw new AIEntityContractException(
                "PII sanitization requested for %s.%s but no PIIDetectionService is available"
                    .formatted(entityClass.getName(), name)
            );
        }
    }

    private void validateContextField(
        Class<?> entityClass,
        AIValueAccessor accessor,
        String key,
        AIContextDataType dataType,
        String format,
        Set<AIContextDestination> destinations,
        String description,
        int priority,
        boolean sanitizePII
    ) {
        if (destinations.isEmpty()) {
            throw new AIEntityContractException(
                "Context field %s.%s has no destination".formatted(entityClass.getName(), key)
            );
        }
        validatePriority(priority, key);
        if (description != null && description.length() > 500) {
            throw new AIEntityContractException("Context description exceeds 500 characters for " + key);
        }
        validateDataType(accessor.valueType(), dataType, key);
        validateFormat(accessor.valueType(), dataType, format, key);
        if (sanitizePII
            && (piiDetectionServiceProvider == null
                || piiDetectionServiceProvider.getIfAvailable() == null)) {
            throw new AIEntityContractException(
                "PII sanitization requested for %s.%s but no PIIDetectionService is available"
                    .formatted(entityClass.getName(), key)
            );
        }
    }

    private void validateDataType(Class<?> type, AIContextDataType dataType, String key) {
        Class<?> boxed = boxed(type);
        boolean valid = switch (dataType) {
            case AUTO, ID, JSON -> true;
            case STRING -> CharSequence.class.isAssignableFrom(boxed) || boxed.isEnum();
            case NUMBER -> Number.class.isAssignableFrom(boxed);
            case BOOLEAN -> boxed == Boolean.class;
            case DATE -> TemporalAccessor.class.isAssignableFrom(boxed)
                || Date.class.isAssignableFrom(boxed);
            case ENUM -> boxed.isEnum();
        };
        if (!valid) {
            throw new AIEntityContractException(
                "Context field %s declares %s for incompatible Java type %s"
                    .formatted(key, dataType, type.getName())
            );
        }
    }

    private void validateFormat(
        Class<?> type,
        AIContextDataType dataType,
        String format,
        String key
    ) {
        if (!StringUtils.hasText(format)) {
            return;
        }
        try {
            Class<?> boxedType = boxed(type);
            if ((dataType == AIContextDataType.DATE
                || dataType == AIContextDataType.AUTO)
                && Date.class.isAssignableFrom(boxedType)) {
                new SimpleDateFormat(format);
            } else if (dataType == AIContextDataType.DATE
                || (dataType == AIContextDataType.AUTO
                    && TemporalAccessor.class.isAssignableFrom(boxedType))) {
                DateTimeFormatter.ofPattern(format);
            } else if (dataType == AIContextDataType.NUMBER
                || (dataType == AIContextDataType.AUTO
                    && Number.class.isAssignableFrom(boxedType))) {
                new DecimalFormat(format);
            } else {
                throw new AIEntityContractException(
                    "Format is supported only for date and number context fields: " + key
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new AIEntityContractException("Invalid format for context field " + key, exception);
        }
    }

    private void validatePriority(int priority, String field) {
        if (priority < 0 || priority > 100) {
            throw new AIEntityContractException(
                "Priority must be between 0 and 100 for " + field
            );
        }
    }

    private AISearchPreprocessing tightenPreprocessing(
        AISearchPreprocessing annotation,
        AISearchPreprocessing override,
        String field
    ) {
        if (annotation == AISearchPreprocessing.SANITIZE
            && override != AISearchPreprocessing.SANITIZE) {
            throw new AIEntityContractException(
                "Config cannot disable SANITIZE preprocessing for " + field
            );
        }
        return override;
    }

    private IndexingStrategy requireConcreteDefaultStrategy(
        IndexingStrategy strategy,
        String entityType
    ) {
        if (strategy == null || strategy == IndexingStrategy.AUTO) {
            throw new AIEntityContractException(
                "Default indexing strategy must be concrete for " + entityType
            );
        }
        return strategy;
    }

    private Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private String requireText(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new AIEntityContractException(label + " is required");
        }
        return value.trim();
    }

    private <E extends Enum<E>> Set<E> enumSet(E[] values) {
        if (values == null || values.length == 0) {
            return Set.of();
        }
        EnumSet<E> result = EnumSet.noneOf(values[0].getDeclaringClass());
        result.addAll(List.of(values));
        return result;
    }

    private <A extends Annotation> A annotation(
        AIValueAccessor accessor,
        Class<A> annotationType
    ) {
        AnnotatedElement element = switch (accessor) {
            case FieldValueAccessor fieldAccessor -> fieldAccessor.field;
            case MethodValueAccessor methodAccessor -> methodAccessor.method;
            default -> throw new AIEntityContractException("Unsupported accessor " + accessor);
        };
        return element.getAnnotation(annotationType);
    }

    private Comparator<AISearchFieldDescriptor> fieldOrder() {
        return Comparator.comparingInt(AISearchFieldDescriptor::priority)
            .reversed()
            .thenComparingInt(AISearchFieldDescriptor::declarationOrder);
    }

    private Comparator<AIContextFieldDescriptor> contextOrder() {
        return Comparator.comparingInt(AIContextFieldDescriptor::priority)
            .reversed()
            .thenComparingInt(AIContextFieldDescriptor::declarationOrder);
    }

    private static final class FieldValueAccessor implements AIValueAccessor {
        private final Field field;

        private FieldValueAccessor(Field field) {
            this.field = field;
            if (!field.trySetAccessible()) {
                throw new AIEntityContractException("Cannot access annotated field " + field);
            }
        }

        @Override
        public String memberName() {
            return field.getName();
        }

        @Override
        public Class<?> valueType() {
            return field.getType();
        }

        @Override
        public Object read(Object target) {
            try {
                return field.get(target);
            } catch (IllegalAccessException exception) {
                throw new AIEntityContractException("Cannot read field " + field, exception);
            }
        }
    }

    private static final class MethodValueAccessor implements AIValueAccessor {
        private final Method method;

        private MethodValueAccessor(Method method) {
            this.method = method;
            if (!method.trySetAccessible()) {
                throw new AIEntityContractException("Cannot access annotated method " + method);
            }
        }

        @Override
        public String memberName() {
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                return Character.toLowerCase(name.charAt(3)) + name.substring(4);
            }
            if (name.startsWith("is") && name.length() > 2) {
                return Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            return name;
        }

        @Override
        public Class<?> valueType() {
            return method.getReturnType();
        }

        @Override
        public Object read(Object target) {
            try {
                return method.invoke(target);
            } catch (ReflectiveOperationException exception) {
                throw new AIEntityContractException("Cannot invoke method " + method, exception);
            }
        }
    }

    private static final class MemberIdentityResolver implements EntityIdentityResolver {
        private final Class<?> entityClass;
        private final AIValueAccessor accessor;
        private final String source;
        private final ObjectMapper objectMapper;

        private MemberIdentityResolver(
            Class<?> entityClass,
            AIValueAccessor accessor,
            String source,
            ObjectMapper objectMapper
        ) {
            this.entityClass = entityClass;
            this.accessor = accessor;
            this.source = source;
            this.objectMapper = objectMapper;
        }

        @Override
        public boolean supports(Class<?> candidate) {
            return entityClass.isAssignableFrom(candidate);
        }

        @Override
        public Object resolveIdentity(Object entity) {
            return canonicalIdentity(accessor.read(entity), objectMapper);
        }

        @Override
        public String source() {
            return source;
        }
    }

    private static final class CanonicalizingIdentityResolver implements EntityIdentityResolver {
        private final EntityIdentityResolver delegate;
        private final ObjectMapper objectMapper;

        private CanonicalizingIdentityResolver(
            EntityIdentityResolver delegate,
            ObjectMapper objectMapper
        ) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
        }

        @Override
        public boolean supports(Class<?> entityClass) {
            return delegate.supports(entityClass);
        }

        @Override
        public Object resolveIdentity(Object entity) {
            return canonicalIdentity(delegate.resolveIdentity(entity), objectMapper);
        }

        @Override
        public String source() {
            return delegate.source();
        }
    }

    private static String canonicalIdentity(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence
            || value instanceof Number
            || value instanceof java.util.UUID
            || value instanceof Enum<?>) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new AIEntityContractException(
                "Unable to serialize composite entity identity",
                exception
            );
        }
    }
}
