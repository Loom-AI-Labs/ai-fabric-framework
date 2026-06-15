package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionSideEffectLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps file-based connector action definitions into the framework's canonical {@link AIActionMetaData} contract.
 */
@Slf4j
public final class ConnectorActionMetadataMapper {

    private ConnectorActionMetadataMapper() {
    }

    /**
     * Convert a connector action definition to canonical metadata.
     *
     * <p>This metadata is used for prompt-visible action specs and deterministic parameter validation.</p>
     *
     * @param definition connector action definition (required)
     * @return canonical metadata
     */
    public static AIActionMetaData toMetadata(ConnectorActionDefinition definition) {
        if (definition == null || !StringUtils.hasText(definition.name())) {
            throw new IllegalArgumentException("definition.name is required");
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        Map<String, AIActionParamSchema> schemas = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();

        List<ConnectorActionParamDefinition> params = definition.params() != null ? definition.params() : List.of();
        for (ConnectorActionParamDefinition param : params) {
            if (param == null || !StringUtils.hasText(param.name())) {
                continue;
            }
            String name = param.name().trim();
            String desc = StringUtils.hasText(param.description()) ? param.description().trim() : name;
            desc = desc + (param.required() ? " (required)" : " (optional)");
            parameters.put(name, desc);

            schemas.put(name, toParamSchema(param));
            if (param.required()) {
                required.add(name);
            }
        }

        ActionAccessMode accessMode = definition.accessMode() != null ? definition.accessMode() : ActionAccessMode.READ;
        return AIActionMetaData.builder()
            .name(definition.name().trim())
            .displayName(StringUtils.hasText(definition.displayName()) ? definition.displayName().trim() : definition.name().trim())
            .description(StringUtils.hasText(definition.description()) ? definition.description().trim() : null)
            .category(StringUtils.hasText(definition.category()) ? definition.category().trim() : null)
            .accessMode(accessMode)
            .anonymousAllowed(definition.anonymousAllowed())
            .confirmationRequired(definition.requiresConfirmation())
            .groundingEligible(definition.groundingEligible())
            .readActionResolutionEligible(definition.readActionResolutionEligible())
            .sideEffectLevel(ActionSideEffectLevel.fromAccessMode(accessMode))
            .resultPresentationHint(definition.resultPresentationHint())
            .builtInModuleId(StringUtils.hasText(definition.builtInModuleId()) ? definition.builtInModuleId().trim() : null)
            .builtInCardId(StringUtils.hasText(definition.builtInCardId()) ? definition.builtInCardId().trim() : null)
            .provenance(definition.provenance())
            .parameters(Collections.unmodifiableMap(parameters))
            .parameterSchemas(Collections.unmodifiableMap(schemas))
            .requiredParameters(Collections.unmodifiableSet(required))
            .build();
    }

    private static AIActionParamSchema toParamSchema(ConnectorActionParamDefinition param) {
        if (param == null) {
            return null;
        }
        LinkedHashMap<String, AIActionParamSchema> properties = new LinkedHashMap<>();
        if (param.properties() != null) {
            for (Map.Entry<String, ConnectorActionParamDefinition> entry : param.properties().entrySet()) {
                if (entry == null || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                properties.put(entry.getKey().trim(), toParamSchema(entry.getValue()));
            }
        }
        return AIActionParamSchema.builder()
            .name(StringUtils.hasText(param.name()) ? param.name().trim() : null)
            .description(StringUtils.hasText(param.description()) ? param.description().trim() : null)
            .type(param.type())
            .required(param.required())
            .batchTargets(param.batchTargets())
            .items(toParamSchema(param.items()))
            .properties(properties.isEmpty() ? Map.of() : Map.copyOf(properties))
            .requiredProperties(param.requiredProperties() != null ? List.copyOf(param.requiredProperties()) : List.of())
            .pattern(StringUtils.hasText(param.pattern()) ? param.pattern().trim() : null)
            .allowedValues(param.allowedValues() != null ? List.copyOf(param.allowedValues()) : List.of())
            .min(param.min())
            .max(param.max())
            .defaultValue(param.defaultValue())
            .visibility(StringUtils.hasText(param.visibility()) ? param.visibility().trim() : null)
            .askUser(param.askUser())
            .resolveFrom(param.resolveFrom() != null ? Map.copyOf(param.resolveFrom()) : Map.of())
            .evidenceBound(param.evidenceBound())
            .evidenceKeys(param.evidenceKeys() != null ? List.copyOf(param.evidenceKeys()) : List.of())
            .evidenceFallbackPolicy(param.evidenceFallbackPolicy())
            .build();
    }

    /**
     * Extract sensitive param names from a connector action definition.
     *
     * <p>Sensitive params are redacted from confirmation messages by default.</p>
     */
    public static Set<String> extractSensitiveParams(ConnectorActionDefinition definition) {
        if (definition == null || definition.params() == null || definition.params().isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (ConnectorActionParamDefinition param : definition.params()) {
            if (param == null || !param.sensitive() || !StringUtils.hasText(param.name())) {
                continue;
            }
            out.add(param.name().trim());
        }
        return Set.copyOf(out);
    }
}
