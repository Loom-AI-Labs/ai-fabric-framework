package ai.fabric.intent.action;

import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.ActionFacts;
import ai.fabric.intent.action.annotation.Param;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;

/**
 * Discovers {@link AIAction} beans and exposes lookup utilities by action name.
 *
 * <p>Greenfield: action contracts are unified across sources (annotations + file-based catalogs + future DB catalogs).</p>
 */
@Slf4j
@Service
public class AIActionRegistry {

    private static final int PARAM_SCHEMA_MAX_DEPTH = 4;

    private final ApplicationContext applicationContext;
    private final ObjectProvider<ConversionService> conversionServiceProvider;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final ObjectProvider<AIActionRegistryContributor> contributorsProvider;

    private volatile Map<String, AIActionHandler> handlerByActionName = Collections.emptyMap();
    private volatile Map<String, AIActionMetaData> metadataByActionName = Collections.emptyMap();

    public AIActionRegistry(ApplicationContext applicationContext,
                            ObjectProvider<ConversionService> conversionServiceProvider,
                            ObjectProvider<ObjectMapper> objectMapperProvider,
                            ObjectProvider<AIActionRegistryContributor> contributorsProvider) {
        this.applicationContext = applicationContext;
        this.conversionServiceProvider = conversionServiceProvider;
        this.objectMapperProvider = objectMapperProvider;
        this.contributorsProvider = contributorsProvider;
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    /**
     * Rebuild the registry from all known sources (annotations + contributors) and atomically swap the snapshot.
     *
     * <p>Greenfield: used by opt-in modules that support runtime registration (e.g. DB-backed connector action catalogs).</p>
     */
    public synchronized void refresh() {
        RegistrySnapshot snapshot = buildSnapshot();
        handlerByActionName = snapshot.handlerByActionName;
        metadataByActionName = snapshot.metadataByActionName;
        if (handlerByActionName.isEmpty()) {
            log.info("AIActionRegistry refreshed with 0 action(s)");
        } else {
            log.info("AIActionRegistry refreshed with {} action(s)", handlerByActionName.size());
        }
    }

    private RegistrySnapshot buildSnapshot() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(AIAction.class);
        if (beans == null) {
            beans = Map.of();
        }

        Map<String, AIActionHandler> handlerMap = new LinkedHashMap<>();
        Map<String, AIActionMetaData> metadataMap = new LinkedHashMap<>();
        Map<String, String> sourceByActionKey = new LinkedHashMap<>();

        if (!beans.isEmpty()) {
            ActionMethodArgumentBinder binder = new ActionMethodArgumentBinder(
                conversionServiceProvider != null ? conversionServiceProvider.getIfAvailable() : null,
                objectMapperProvider != null ? objectMapperProvider.getIfAvailable(ObjectMapper::new) : new ObjectMapper()
            );

            for (Map.Entry<String, Object> entry : beans.entrySet()) {
                Object bean = entry.getValue();
                if (bean == null) {
                    continue;
                }

                Class<?> targetClass = AopUtils.getTargetClass(bean);
                AIAction action = AnnotatedElementUtils.findMergedAnnotation(targetClass, AIAction.class);
                if (action == null || !StringUtils.hasText(action.name())) {
                    continue;
                }

                Method executeMethod = findSingleMethod(targetClass, ActionExecute.class, "@ActionExecute");
                Method allowedMethod = findOptionalSingleMethod(targetClass, ActionAllowed.class, "@ActionAllowed");
                Method confirmationMethod = findOptionalSingleMethod(targetClass, ActionConfirmation.class, "@ActionConfirmation");
                Method factsMethod = findOptionalSingleMethod(targetClass, ActionFacts.class, "@ActionFacts");

                AIActionMetaData meta = buildMetadata(action, executeMethod);
                String key = AIActionNames.normalize(meta.getName());
                if (handlerMap.containsKey(key)) {
                    String existingSource = sourceByActionKey.getOrDefault(key, "unknown");
                    throw new IllegalStateException("Action '" + meta.getName() + "' registered twice: "
                        + existingSource + " + annotations");
                }

                AIActionHandler handler = new AnnotatedAIActionHandler(bean, action, meta, executeMethod, allowedMethod, confirmationMethod, factsMethod, binder);
                handlerMap.put(key, handler);
                metadataMap.put(key, meta);
                sourceByActionKey.put(key, "annotations");
            }
        }

        // Merge in additional action sources (connector catalogs, DB catalogs, etc.)
        if (contributorsProvider != null) {
            for (AIActionRegistryContributor contributor : contributorsProvider) {
                if (contributor == null) {
                    continue;
                }
                List<AIActionHandler> contributed = contributor.getHandlers();
                if (contributed == null || contributed.isEmpty()) {
                    continue;
                }
                String source = contributor.getSourceName();
                for (AIActionHandler handler : contributed) {
                    if (handler == null || handler.getActionMetadata() == null || !StringUtils.hasText(handler.getActionMetadata().getName())) {
                        continue;
                    }
                    AIActionMetaData meta = handler.getActionMetadata();
                    String key = AIActionNames.normalize(meta.getName());
                    if (handlerMap.containsKey(key)) {
                        String existingSource = sourceByActionKey.getOrDefault(key, "unknown");
                        throw new IllegalStateException("Action '" + meta.getName() + "' registered twice: "
                            + existingSource + " + " + (StringUtils.hasText(source) ? source : "contributor"));
                    }
                    handlerMap.put(key, handler);
                    metadataMap.put(key, meta);
                    sourceByActionKey.put(key, StringUtils.hasText(source) ? source : contributor.getClass().getSimpleName());
                }
            }
        }

        Map<String, AIActionHandler> handlers = Collections.unmodifiableMap(handlerMap);
        Map<String, AIActionMetaData> metadata = Collections.unmodifiableMap(metadataMap);
        return new RegistrySnapshot(handlers, metadata);
    }

    public Optional<AIActionHandler> findHandler(String actionName) {
        if (!StringUtils.hasText(actionName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlerByActionName.get(AIActionNames.normalize(actionName)));
    }

    public Optional<AIActionMetaData> findMetadata(String actionName) {
        if (!StringUtils.hasText(actionName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(metadataByActionName.get(AIActionNames.normalize(actionName)));
    }

    public java.util.List<AIActionMetaData> getAllMetadata() {
        return java.util.List.copyOf(metadataByActionName.values());
    }

    public Map<String, AIActionHandler> getHandlerMap() {
        return handlerByActionName;
    }

    private record RegistrySnapshot(Map<String, AIActionHandler> handlerByActionName,
                                    Map<String, AIActionMetaData> metadataByActionName) {
    }

    private Method findSingleMethod(Class<?> targetClass, Class<? extends Annotation> annotationType, String label) {
        Map<Method, ?> selected = MethodIntrospector.selectMethods(
            targetClass,
            (MethodIntrospector.MetadataLookup<Annotation>) method ->
                AnnotatedElementUtils.findMergedAnnotation(method, annotationType)
        );
        if (selected.isEmpty()) {
            throw new IllegalStateException("AIAction class must declare exactly one " + label + " method: " + targetClass.getName());
        }
        if (selected.size() > 1) {
            throw new IllegalStateException("AIAction class must declare exactly one " + label + " method (found " + selected.size() + "): " + targetClass.getName());
        }
        return selected.keySet().iterator().next();
    }

    private Method findOptionalSingleMethod(Class<?> targetClass, Class<? extends Annotation> annotationType, String label) {
        Map<Method, ?> selected = MethodIntrospector.selectMethods(
            targetClass,
            (MethodIntrospector.MetadataLookup<Annotation>) method ->
                AnnotatedElementUtils.findMergedAnnotation(method, annotationType)
        );
        if (selected.isEmpty()) {
            return null;
        }
        if (selected.size() > 1) {
            throw new IllegalStateException("AIAction class must declare at most one " + label + " method (found " + selected.size() + "): " + targetClass.getName());
        }
        return selected.keySet().iterator().next();
    }

    private AIActionMetaData buildMetadata(AIAction action, Method executeMethod) {
        Map<String, String> parameters = new LinkedHashMap<>();
        Map<String, AIActionParamSchema> parameterSchemas = new LinkedHashMap<>();
        Set<String> requiredParameters = new LinkedHashSet<>();

        for (Parameter parameter : executeMethod.getParameters()) {
            if (ActionMethodArgumentBinder.isContextParameter(parameter.getType())) {
                continue;
            }
            Param param = parameter.getAnnotation(Param.class);
            if (param == null) {
                throw new IllegalStateException("AIAction execute method parameter must be annotated with @Param: "
                    + executeMethod.getDeclaringClass().getName() + "#" + executeMethod.getName());
            }

            String name = StringUtils.hasText(param.value()) ? param.value() : parameter.getName();
            if (!StringUtils.hasText(name) || name.startsWith("arg")) {
                throw new IllegalStateException("AIAction parameter name is not available. "
                    + "Compile with -parameters or set @Param(\"name\"). "
                    + "Offending parameter in " + executeMethod.getDeclaringClass().getName() + "#" + executeMethod.getName());
            }

            String description = StringUtils.hasText(param.description())
                ? param.description().trim()
                : parameter.getType().getSimpleName();

            description = description + (param.required() ? " (required)" : " (optional)");
            parameters.put(name, description);
            parameterSchemas.put(name, buildParamSchema(name, param.description(), param, parameter.getParameterizedType(), 0));
            if (param.required()) {
                requiredParameters.add(name);
            }
        }

        return AIActionMetaData.builder()
            .name(action.name())
            .displayName(humanizeActionName(action.name()))
            .description(action.description())
            .category(action.category())
            .accessMode(action.accessMode())
            .anonymousAllowed(action.anonymousAllowed())
            .confirmationRequired(action.requiresConfirmation())
            .groundingEligible(defaultGroundingEligible(action.accessMode()))
            .readActionResolutionEligible(action.readActionResolutionEligible())
            .sideEffectLevel(ActionSideEffectLevel.fromAccessMode(action.accessMode()))
            .resultPresentationHint(defaultPresentationHint(action.accessMode()))
            .provenance(AIContributionProvenance.builder()
                .contributionType("ACTION")
                .sourceType("ANNOTATION")
                .sourceId(action.name())
                .sourceLocation(executeMethod.getDeclaringClass().getName())
                .build())
            .parameters(Collections.unmodifiableMap(parameters))
            .parameterSchemas(Collections.unmodifiableMap(parameterSchemas))
            .requiredParameters(Collections.unmodifiableSet(requiredParameters))
            .build();
    }

    private String humanizeActionName(String actionName) {
        if (!StringUtils.hasText(actionName)) {
            return null;
        }
        String[] parts = actionName.trim().split("[_\\-\\s]+");
        StringBuilder out = new StringBuilder(actionName.length() + 8);
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            String normalized = part.trim().toLowerCase(Locale.ROOT);
            out.append(Character.toUpperCase(normalized.charAt(0)));
            if (normalized.length() > 1) {
                out.append(normalized.substring(1));
            }
        }
        return out.isEmpty() ? actionName.trim() : out.toString();
    }

    private boolean defaultGroundingEligible(ActionAccessMode accessMode) {
        return accessMode == ActionAccessMode.READ || accessMode == ActionAccessMode.READ_WRITE;
    }

    private ActionResultPresentationHint defaultPresentationHint(ActionAccessMode accessMode) {
        if (accessMode == ActionAccessMode.WRITE_ONLY) {
            return ActionResultPresentationHint.STATUS;
        }
        return ActionResultPresentationHint.DEFAULT;
    }

    private AIActionParamSchema buildParamSchema(String name,
                                                 String description,
                                                 Param param,
                                                 Type type,
                                                 int depth) {
        if (depth >= PARAM_SCHEMA_MAX_DEPTH || type == null) {
            return AIActionParamSchema.builder()
                .name(name)
                .description(StringUtils.hasText(description) ? description.trim() : null)
                .type(AIActionParamType.UNKNOWN)
                .required(param != null ? param.required() : null)
                .batchTargets(param != null ? param.batchTargets() : null)
                .build();
        }

        Class<?> raw = rawClass(type);
        AIActionParamType paramType = toParamType(raw);

        AIActionParamSchema.AIActionParamSchemaBuilder builder = AIActionParamSchema.builder()
            .name(name)
            .description(StringUtils.hasText(description) ? description.trim() : null)
            .type(paramType)
            .required(param != null ? param.required() : null)
            .batchTargets(param != null && param.batchTargets() ? Boolean.TRUE : null);

        if (param != null) {
            if (StringUtils.hasText(param.pattern())) {
                builder.pattern(param.pattern());
            }
            if (param.allowedValues() != null && param.allowedValues().length > 0) {
                List<String> allowed = java.util.Arrays.stream(param.allowedValues())
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
                if (!allowed.isEmpty()) {
                    builder.allowedValues(allowed);
                }
            } else if (raw != null && raw.isEnum()) {
                @SuppressWarnings("unchecked")
                Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) raw;
                builder.allowedValues(java.util.Arrays.stream(enumClass.getEnumConstants())
                    .map(Enum::name)
                    .toList());
            }
            if (param.min() != Long.MIN_VALUE) {
                builder.min(param.min());
            }
            if (param.max() != Long.MAX_VALUE) {
                builder.max(param.max());
            }
        } else if (raw != null && raw.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) raw;
            builder.allowedValues(java.util.Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .toList());
        }

        if (paramType == AIActionParamType.ARRAY) {
            Type itemType = resolveItemType(type, raw);
            builder.items(buildParamSchema(null, null, null, itemType, depth + 1));
        } else if (paramType == AIActionParamType.OBJECT) {
            if (raw != null && raw.isRecord()) {
                Map<String, AIActionParamSchema> props = new LinkedHashMap<>();
                List<String> requiredProps = new java.util.ArrayList<>();
                for (RecordComponent component : raw.getRecordComponents()) {
                    if (component == null || !StringUtils.hasText(component.getName())) {
                        continue;
                    }
                    String propName = component.getName();
                    props.put(propName, buildParamSchema(propName, component.getType().getSimpleName(), null, component.getGenericType(), depth + 1));
                    requiredProps.add(propName);
                }
                if (!props.isEmpty()) {
                    builder.properties(Collections.unmodifiableMap(props));
                    builder.requiredProperties(List.copyOf(requiredProps));
                }
            }
        }

        return builder.build();
    }

    private AIActionParamType toParamType(Class<?> raw) {
        if (raw == null) {
            return AIActionParamType.UNKNOWN;
        }
        if (raw == String.class || raw == CharSequence.class) {
            return AIActionParamType.STRING;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return AIActionParamType.BOOLEAN;
        }
        if (isIntegerType(raw)) {
            return AIActionParamType.INTEGER;
        }
        if (Number.class.isAssignableFrom(raw) || raw == BigDecimal.class) {
            return AIActionParamType.NUMBER;
        }
        if (raw.isEnum()) {
            return AIActionParamType.STRING;
        }
        if (raw.isArray() || java.util.Collection.class.isAssignableFrom(raw)) {
            return AIActionParamType.ARRAY;
        }
        if (java.util.Map.class.isAssignableFrom(raw) || raw.isRecord()) {
            return AIActionParamType.OBJECT;
        }
        return AIActionParamType.UNKNOWN;
    }

    private boolean isIntegerType(Class<?> type) {
        return type == int.class
            || type == Integer.class
            || type == long.class
            || type == Long.class
            || type == short.class
            || type == Short.class
            || type == byte.class
            || type == Byte.class
            || type == BigInteger.class;
    }

    private Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> clazz) {
                return clazz;
            }
        }
        return null;
    }

    private Type resolveItemType(Type declared, Class<?> raw) {
        if (raw != null && raw.isArray()) {
            return raw.getComponentType();
        }
        if (declared instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args != null && args.length >= 1 && args[0] != null) {
                return args[0];
            }
        }
        return Object.class;
    }

}
