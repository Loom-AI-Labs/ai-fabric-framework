package ai.fabric.intent.action.connector.registry.service;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
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
        validateSupportedDbActionShape(definition);

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
            validateSupportedDbParamShape(param);
        }

        validateConfirmationTemplate(definition.name().trim(), definition.confirmationMessage(), paramNames);
    }

    private void validateSupportedDbActionShape(ConnectorActionDefinition definition) {
        String actionName = definition.name().trim();
        if (StringUtils.hasText(definition.displayName()) && !actionName.equals(definition.displayName().trim())) {
            throw new IllegalArgumentException("DB action registry does not persist displayName separately for action '" + actionName + "'. Use name as the display label or use the file-based action catalog.");
        }
        ActionResultPresentationHint expectedHint = definition.accessMode() == ActionAccessMode.WRITE_ONLY
            ? ActionResultPresentationHint.STATUS
            : ActionResultPresentationHint.DEFAULT;
        if (definition.resultPresentationHint() != null && definition.resultPresentationHint() != expectedHint) {
            throw new IllegalArgumentException("DB action registry only supports resultPresentationHint '" + expectedHint + "' for action '" + actionName + "'. Use the file-based action catalog for custom presentation hints.");
        }
        if (StringUtils.hasText(definition.builtInModuleId()) || StringUtils.hasText(definition.builtInCardId())) {
            throw new IllegalArgumentException("DB action registry does not support built-in module/card bindings for action '" + actionName + "'. Use the file-based action catalog.");
        }
        if (definition.provenance() != null) {
            throw new IllegalArgumentException("DB action registry does not support action provenance metadata for action '" + actionName + "'. Use the file-based action catalog.");
        }
        if (definition.postPolicies() != null && !definition.postPolicies().isEmpty()) {
            throw new IllegalArgumentException("DB action registry does not support postPolicies for action '" + actionName + "'. Use the file-based action catalog.");
        }
        if (definition.llmFacts() != null) {
            throw new IllegalArgumentException("DB action registry does not support llmFacts for action '" + actionName + "'. Use the file-based action catalog.");
        }
        if (StringUtils.hasText(definition.adapterType())
            || (definition.execution() != null && !definition.execution().isEmpty())
            || (definition.mcpServers() != null && !definition.mcpServers().isEmpty())) {
            throw new IllegalArgumentException("DB action registry does not support adapter/execution runtime config for action '" + actionName + "'. Use the file-based action catalog for mcp-tool actions.");
        }
    }

    private void validateSupportedDbParamShape(ConnectorActionParamDefinition param) {
        String paramName = param.name().trim();
        if (param.items() != null || (param.properties() != null && !param.properties().isEmpty()) || (param.requiredProperties() != null && !param.requiredProperties().isEmpty())) {
            throw new IllegalArgumentException("DB action registry only supports flat params; nested schema metadata is unsupported for param '" + paramName + "'. Use the file-based action catalog.");
        }
        if (StringUtils.hasText(param.visibility())
            || param.askUser() != null
            || (param.resolveFrom() != null && !param.resolveFrom().isEmpty())
            || param.evidenceBound()
            || (param.evidenceKeys() != null && !param.evidenceKeys().isEmpty())
            || StringUtils.hasText(param.evidenceFallbackPolicy())) {
            throw new IllegalArgumentException("DB action registry does not support assistant-resolution/evidence metadata for param '" + paramName + "'. Use the file-based action catalog.");
        }
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
