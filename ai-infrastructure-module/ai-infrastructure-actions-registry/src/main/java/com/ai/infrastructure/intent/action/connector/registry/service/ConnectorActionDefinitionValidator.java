package com.ai.infrastructure.intent.action.connector.registry.service;

import com.ai.infrastructure.intent.action.connector.ConnectorActionDefinition;
import com.ai.infrastructure.intent.action.connector.ConnectorActionParamDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConnectorActionDefinitionValidator {

    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)(?:\\s*\\|\\s*([^{}]*?))?\\s*}}");

    public void validate(ConnectorActionDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("action definition is required");
        }
        if (!StringUtils.hasText(definition.name())) {
            throw new IllegalArgumentException("action.name is required");
        }
        if (definition.accessMode() == null) {
            throw new IllegalArgumentException("action.accessMode is required");
        }

        List<ConnectorActionParamDefinition> params = definition.params();
        if (params == null) {
            throw new IllegalArgumentException("action.params is required (use an empty list if none)");
        }

        Set<String> paramNames = new LinkedHashSet<>();
        for (ConnectorActionParamDefinition param : params) {
            if (param == null) {
                continue;
            }
            if (!StringUtils.hasText(param.name())) {
                throw new IllegalArgumentException("param.name is required");
            }
            if (param.type() == null) {
                throw new IllegalArgumentException("param.type is required for param '" + param.name() + "'");
            }
            String key = param.name().trim().toLowerCase(Locale.ROOT);
            if (!paramNames.add(key)) {
                throw new IllegalArgumentException("Duplicate param name '" + param.name().trim() + "'");
            }
            if (param.min() != null && param.max() != null && param.min() > param.max()) {
                throw new IllegalArgumentException("param.min must be <= param.max for param '" + param.name().trim() + "'");
            }
        }

        validateConfirmationTemplate(definition.name().trim(), definition.confirmationMessage(), paramNames);
    }

    private void validateConfirmationTemplate(String actionName, String template, Set<String> paramNames) {
        if (!StringUtils.hasText(template)) {
            return;
        }
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (!StringUtils.hasText(placeholder)) {
                continue;
            }
            String normalized = placeholder.trim().toLowerCase(Locale.ROOT);
            if (!paramNames.contains(normalized)) {
                throw new IllegalArgumentException("confirmationMessage placeholder '{{" + placeholder + "}}' does not match any declared param for action '" + actionName + "'");
            }
        }
    }
}
