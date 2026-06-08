package com.ai.infrastructure.intent.action;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes {@link com.ai.infrastructure.intent.action.annotation.AIAction}-declared actions as prompt-visible actions for intent extraction.
 */
@Service
@RequiredArgsConstructor
public class RegisteredActionsAIActionProvider implements AIActionProvider {

    private final AIActionRegistry actionRegistry;

    @Override
    public List<ActionInfo> getAvailableActions() {
        if (actionRegistry == null) {
            return List.of();
        }
        return actionRegistry.getAllMetadata().stream()
            .filter(Objects::nonNull)
            .map(meta -> ActionInfo.builder()
                .name(meta.getName())
                .description(meta.getDescription())
                .category(meta.getCategory())
                .parameters(publicParameters(meta))
                .parameterSchemas(publicParameterSchemas(meta))
                .build())
            .filter(ActionInfo::hasValidName)
            .toList();
    }

    @Override
    public String getProviderName() {
        return "registered-actions";
    }

    private Map<String, String> publicParameters(AIActionMetaData meta) {
        if (meta == null || meta.getParameters() == null || meta.getParameters().isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        meta.getParameters().forEach((name, description) -> {
            if (StringUtils.hasText(name) && isUserVisible(meta, name)) {
                out.put(name.trim(), description);
            }
        });
        return Map.copyOf(out);
    }

    private Map<String, AIActionParamSchema> publicParameterSchemas(AIActionMetaData meta) {
        if (meta == null || meta.getParameterSchemas() == null || meta.getParameterSchemas().isEmpty()) {
            return Map.of();
        }
        Map<String, AIActionParamSchema> out = new LinkedHashMap<>();
        meta.getParameterSchemas().forEach((name, schema) -> {
            if (StringUtils.hasText(name) && isUserVisible(meta, name)) {
                out.put(name.trim(), schema);
            }
        });
        return Map.copyOf(out);
    }

    private boolean isUserVisible(AIActionMetaData meta, String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        if ("shopperSessionId".equals(name.trim()) || "confirmationAccepted".equals(name.trim())) {
            return false;
        }
        AIActionParamSchema schema = meta != null && meta.getParameterSchemas() != null
            ? meta.getParameterSchemas().get(name.trim())
            : null;
        if (schema == null && meta != null && meta.getParameterSchemas() != null) {
            for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
                if (entry != null && StringUtils.hasText(entry.getKey()) && name.trim().equalsIgnoreCase(entry.getKey().trim())) {
                    schema = entry.getValue();
                    break;
                }
            }
        }
        if (schema == null) {
            return true;
        }
        if (Boolean.FALSE.equals(schema.getAskUser())) {
            return false;
        }
        String visibility = schema.getVisibility();
        if (!StringUtils.hasText(visibility)) {
            return true;
        }
        String normalized = visibility.trim().toUpperCase(Locale.ROOT);
        return !"INTERNAL".equals(normalized) && !"SECRET".equals(normalized) && !"SYSTEM".equals(normalized);
    }
}
