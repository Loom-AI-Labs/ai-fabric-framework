package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.IntentType;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorParamSupport;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorRule;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorTrigger;
import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationDecisionSupport.ConfirmationResolutionDecision;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConfiguredConfirmationSupport {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private ConfiguredConfirmationSupport() {
    }

    static List<ConfirmationInterceptorRule> configuredConfirmationInterceptorRules(
        ObjectProvider<ConfirmationInterceptorCatalogProvider> confirmationInterceptorCatalogProvider
    ) {
        if (confirmationInterceptorCatalogProvider == null) {
            return List.of();
        }
        ConfirmationInterceptorCatalogProvider provider = confirmationInterceptorCatalogProvider.getIfAvailable();
        if (provider == null || provider.getRules() == null || provider.getRules().isEmpty()) {
            return List.of();
        }
        return provider.getRules();
    }

    static ConfirmationInterceptorRule findMatchingConfiguredConfirmationRule(List<ConfirmationInterceptorRule> rules,
                                                                              PendingAction pending,
                                                                              ConfirmationResolutionDecision decision) {
        if (rules == null || rules.isEmpty() || pending == null || !StringUtils.hasText(pending.action())) {
            return null;
        }
        IntentType confirmationIntent = switch (decision) {
            case POSITIVE -> IntentType.CONFIRMATION_POSITIVE;
            case NEGATIVE -> IntentType.CONFIRMATION_NEGATIVE;
            case UNKNOWN -> null;
        };
        if (confirmationIntent == null) {
            return null;
        }
        String actionName = normalizeConfirmationKey(pending.action());
        for (ConfirmationInterceptorRule rule : rules) {
            if (rule == null || rule.trigger() == null || rule.decision() == null) {
                continue;
            }
            ConfirmationInterceptorTrigger trigger = rule.trigger();
            if (trigger.confirmation() != confirmationIntent) {
                continue;
            }
            if (!containsNormalizedConfirmationValue(trigger.pendingActions(), actionName)) {
                continue;
            }
            if (ConfirmationInterceptorParamSupport.isBooleanFlagSet(pending.actionParams(), trigger.onceParam())) {
                continue;
            }
            return rule;
        }
        return null;
    }

    static Map<String, Object> resolveConfiguredConfirmationActionParams(Map<String, Object> params,
                                                                         List<PendingAction> stackSnapshot) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey())) {
                continue;
            }
            Object resolved = resolveConfiguredConfirmationTemplateValue(entry.getValue(), stackSnapshot);
            if (resolved != null) {
                out.put(entry.getKey(), resolved);
            }
        }
        return Map.copyOf(out);
    }

    static Object resolveConfiguredConfirmationTemplateValue(Object raw, List<PendingAction> stackSnapshot) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                Object resolved = resolveConfiguredConfirmationTemplateValue(entry.getValue(), stackSnapshot);
                if (resolved != null) {
                    out.put(String.valueOf(entry.getKey()), resolved);
                }
            }
            return out;
        }
        if (raw instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                Object resolved = resolveConfiguredConfirmationTemplateValue(item, stackSnapshot);
                if (resolved != null) {
                    out.add(resolved);
                }
            }
            return out;
        }
        if (!(raw instanceof String template) || !template.contains("{{")) {
            return raw;
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        if (!matcher.find()) {
            return template;
        }

        matcher.reset();
        if (matcher.matches()) {
            return resolveConfiguredConfirmationExpression(matcher.group(1), stackSnapshot);
        }

        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveConfiguredConfirmationExpression(matcher.group(1), stackSnapshot);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved != null ? String.valueOf(resolved) : ""));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    static Object resolveConfiguredConfirmationExpression(String expression, List<PendingAction> stackSnapshot) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        String raw = expression.trim();
        String path = raw;
        String fallbackToken = null;
        int fallbackSeparator = raw.indexOf('|');
        if (fallbackSeparator >= 0) {
            path = raw.substring(0, fallbackSeparator).trim();
            fallbackToken = raw.substring(fallbackSeparator + 1).trim();
        }

        Object resolved = resolveConfiguredConfirmationPath(path, stackSnapshot);
        if (resolved != null) {
            return resolved;
        }
        return parseConfiguredConfirmationFallbackLiteral(fallbackToken);
    }

    static Object resolveConfiguredConfirmationPath(String path, List<PendingAction> stackSnapshot) {
        if (!StringUtils.hasText(path)) {
            return null;
        }

        PendingAction pending = stackSnapshot != null && !stackSnapshot.isEmpty() ? stackSnapshot.getFirst() : null;
        PendingAction previous = stackSnapshot != null && stackSnapshot.size() > 1 ? stackSnapshot.get(1) : null;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pending", pendingActionTemplateModel(pending));
        root.put("stack", Map.of("previous", pendingActionTemplateModel(previous)));

        Object current = root;
        for (String token : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(token)) {
                return null;
            }
            current = map.get(token);
        }
        return current;
    }

    static Map<String, Object> pendingActionTemplateModel(PendingAction pendingAction) {
        if (pendingAction == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", pendingAction.action());
        out.put("description", pendingAction.description());
        out.put("createdAt", pendingAction.createdAt() != null ? pendingAction.createdAt().toString() : null);
        out.put("actionParams", pendingAction.actionParams() != null ? pendingAction.actionParams() : Map.of());
        return out;
    }

    static Object parseConfiguredConfirmationFallbackLiteral(String fallbackToken) {
        if (!StringUtils.hasText(fallbackToken)) {
            return null;
        }
        String token = fallbackToken.trim();
        if ("true".equalsIgnoreCase(token)) {
            return true;
        }
        if ("false".equalsIgnoreCase(token)) {
            return false;
        }
        try {
            if (token.contains(".")) {
                return Double.parseDouble(token);
            }
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return token;
        }
    }

    static void applyConfiguredConfirmationStackPolicy(ConfirmationInterceptorStackPolicy policy,
                                                       List<PendingAction> workingStack) {
        ConfirmationInterceptorStackPolicy effectivePolicy = policy != null
            ? policy
            : ConfirmationInterceptorStackPolicy.NONE;
        boolean poppedCurrent = false;
        if (effectivePolicy.popCurrent() && !workingStack.isEmpty()) {
            workingStack.remove(0);
            poppedCurrent = true;
        }
        if (!effectivePolicy.popPreviousIfActionIn().isEmpty()) {
            int previousIndex = poppedCurrent ? 0 : 1;
            if (workingStack.size() > previousIndex) {
                PendingAction previous = workingStack.get(previousIndex);
                if (previous != null && containsNormalizedConfirmationValue(effectivePolicy.popPreviousIfActionIn(), previous.action())) {
                    workingStack.remove(previousIndex);
                }
            }
        }
    }

    static PendingAction withBooleanPendingParam(PendingAction pendingAction, String key, boolean value) {
        Map<String, Object> params = pendingAction != null && pendingAction.actionParams() != null
            ? new LinkedHashMap<>(pendingAction.actionParams())
            : new LinkedHashMap<>();
        params.put(key, value);
        return new PendingAction(
            pendingAction.action(),
            Collections.unmodifiableMap(new LinkedHashMap<>(params)),
            pendingAction.description(),
            pendingAction.createdAt() != null ? pendingAction.createdAt() : Instant.now(),
            pendingAction.trustedEvidenceValuesByKey()
        );
    }

    static boolean containsNormalizedConfirmationValue(List<String> values, String candidate) {
        if (values == null || values.isEmpty() || !StringUtils.hasText(candidate)) {
            return false;
        }
        String normalizedCandidate = normalizeConfirmationKey(candidate);
        for (String value : values) {
            if (normalizeConfirmationKey(value).equals(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeConfirmationKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
