package com.ai.infrastructure.chat.resolver;

import com.ai.infrastructure.chat.interception.InterceptionDecision;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.AIActionNames;
import com.ai.infrastructure.intent.action.PendingAction;
import com.ai.infrastructure.intent.action.PendingActionStore;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecision;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorParamSupport;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorTrigger;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

public class ConfiguredConfirmationInterceptorsResolver extends ConfirmationResolverSupport {

    private static final int RESOLVER_PRIORITY = 9;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private final ObjectProvider<ConfirmationInterceptorCatalogProvider> catalogProvider;

    public ConfiguredConfirmationInterceptorsResolver(PendingActionStore pendingActionStore,
                                                      ObjectProvider<ConfirmationInterceptorCatalogProvider> catalogProvider) {
        super(pendingActionStore);
        this.catalogProvider = Objects.requireNonNull(catalogProvider, "catalogProvider");
    }

    @Override
    public boolean canResolve(MultiIntentResponse intentResponse,
                              Map<String, Object> sessionMetadata,
                              PipelineContext context) {
        List<ConfirmationInterceptorRule> rules = rules();
        if (rules.isEmpty()) {
            return false;
        }
        PendingAction pending = peekPending(context);
        if (pending == null || !StringUtils.hasText(pending.action())) {
            return false;
        }
        Intent confirmationIntent = findFirstConfirmationIntent(intentResponse);
        if (confirmationIntent == null) {
            return false;
        }
        return findMatchingRule(rules, pending, confirmationIntent) != null;
    }

    @Override
    public PipelineContext resolve(MultiIntentResponse intentResponse,
                                   Map<String, Object> sessionMetadata,
                                   PipelineContext context) {
        PendingAction pending = peekPending(context);
        Intent confirmationIntent = findFirstConfirmationIntent(intentResponse);
        if (pending == null || confirmationIntent == null) {
            return context;
        }

        List<ConfirmationInterceptorRule> rules = rules();
        ConfirmationInterceptorRule rule = findMatchingRule(rules, pending, confirmationIntent);
        if (rule == null) {
            return context;
        }

        String conversationId = context.getOrchestrationContext() != null ? context.getOrchestrationContext().getConversationId() : null;
        String ownerId = context.getIdentifier();
        List<PendingAction> stackSnapshot = store().getPendingActionStack(conversationId, ownerId);
        List<PendingAction> workingStack = new ArrayList<>(stackSnapshot);

        String onceParam = rule.trigger() != null
            ? ConfirmationInterceptorParamSupport.normalizeOnceParam(rule.trigger().onceParam())
            : null;
        if (StringUtils.hasText(onceParam) && !workingStack.isEmpty()) {
            workingStack.set(0, withBooleanParam(workingStack.get(0), onceParam, true));
        }

        InterceptionDecision decision = toDecision(rule.decision(), stackSnapshot);
        applyStackPolicy(rule.stackPolicy(), workingStack);
        store().replacePendingActionStack(conversationId, ownerId, workingStack);

        PipelineContext updated = context;
        if (decision.confirmedActions() != null) {
            for (String actionName : decision.confirmedActions()) {
                if (StringUtils.hasText(actionName)) {
                    updated = markConfirmed(updated, actionName.trim());
                }
            }
        }

        List<Intent> nextIntents = new ArrayList<>(decision.intents());
        nextIntents.addAll(preservedNonConfirmationIntents(intentResponse, stackSnapshot, decision));

        MultiIntentResponse updatedResponse = MultiIntentResponse.builder()
            .intents(List.copyOf(nextIntents))
            .orchestrationStrategy(intentResponse != null ? intentResponse.getOrchestrationStrategy() : null)
            .metadata(intentResponse != null && intentResponse.getMetadata() != null ? intentResponse.getMetadata() : Map.of())
            .build();

        return updated.toBuilder()
            .intentResponse(updatedResponse)
            .build();
    }

    @Override
    public int getPriority() {
        return RESOLVER_PRIORITY;
    }

    @Override
    public String getResolverName() {
        return "ConfiguredConfirmationInterceptorsResolver";
    }

    private List<ConfirmationInterceptorRule> rules() {
        ConfirmationInterceptorCatalogProvider provider = catalogProvider.getIfAvailable();
        if (provider == null || provider.getRules() == null || provider.getRules().isEmpty()) {
            return List.of();
        }
        return provider.getRules();
    }

    private Intent findFirstConfirmationIntent(MultiIntentResponse intentResponse) {
        if (intentResponse == null || intentResponse.getIntents() == null) {
            return null;
        }
        for (Intent intent : intentResponse.getIntents()) {
            if (intent != null && (intent.getType() == IntentType.CONFIRMATION_POSITIVE || intent.getType() == IntentType.CONFIRMATION_NEGATIVE)) {
                return intent;
            }
        }
        return null;
    }

    private ConfirmationInterceptorRule findMatchingRule(List<ConfirmationInterceptorRule> rules,
                                                         PendingAction pending,
                                                         Intent confirmationIntent) {
        if (rules == null || rules.isEmpty() || pending == null || confirmationIntent == null) {
            return null;
        }
        String actionName = normalize(pending.action());
        for (ConfirmationInterceptorRule rule : rules) {
            if (rule == null || rule.trigger() == null || rule.decision() == null) {
                continue;
            }
            ConfirmationInterceptorTrigger trigger = rule.trigger();
            if (trigger.confirmation() != confirmationIntent.getType()) {
                continue;
            }
            if (!containsNormalized(trigger.pendingActions(), actionName)) {
                continue;
            }
            if (ConfirmationInterceptorParamSupport.isBooleanFlagSet(pending.actionParams(), trigger.onceParam())) {
                continue;
            }
            return rule;
        }
        return null;
    }

    private InterceptionDecision toDecision(ConfirmationInterceptorDecision decision, List<PendingAction> stackSnapshot) {
        if (decision == null || decision.type() == null) {
            throw new IllegalStateException("Configured confirmation interceptor decision is incomplete.");
        }

        return switch (decision.type()) {
            case PROMPT_ACTION -> new InterceptionDecision(
                List.of(actionIntent(decision.action(), resolveActionParams(decision.params(), stackSnapshot))),
                Set.of()
            );
            case EXECUTE_ACTION -> new InterceptionDecision(
                List.of(actionIntent(decision.action(), resolveActionParams(decision.params(), stackSnapshot))),
                Set.of(decision.action())
            );
            case REPLY -> new InterceptionDecision(
                List.of(replyIntent(resolveMessage(decision.message(), stackSnapshot))),
                Set.of()
            );
        };
    }

    private List<Intent> preservedNonConfirmationIntents(MultiIntentResponse intentResponse,
                                                         List<PendingAction> stackSnapshot,
                                                         InterceptionDecision decision) {
        List<Intent> remaining = withoutConfirmationIntents(intentResponse);
        if (remaining.isEmpty()) {
            return List.of();
        }

        Set<String> correlatedActions = correlatedActionKeys(stackSnapshot, decision);
        if (correlatedActions.isEmpty()) {
            return remaining;
        }

        List<Intent> preserved = new ArrayList<>();
        for (Intent intent : remaining) {
            if (intent == null || intent.getType() != IntentType.ACTION) {
                preserved.add(intent);
                continue;
            }
            String actionName = resolveIntentActionName(intent);
            if (!StringUtils.hasText(actionName)) {
                preserved.add(intent);
                continue;
            }
            String normalized = AIActionNames.normalize(actionName);
            if (!correlatedActions.contains(normalized)) {
                preserved.add(intent);
            }
        }
        return List.copyOf(preserved);
    }

    private Set<String> correlatedActionKeys(List<PendingAction> stackSnapshot, InterceptionDecision decision) {
        Set<String> keys = new LinkedHashSet<>();
        if (stackSnapshot != null) {
            for (PendingAction pendingAction : stackSnapshot) {
                if (pendingAction == null || !StringUtils.hasText(pendingAction.action())) {
                    continue;
                }
                keys.add(AIActionNames.normalize(pendingAction.action()));
            }
        }
        if (decision != null && decision.confirmedActions() != null) {
            for (String actionName : decision.confirmedActions()) {
                if (StringUtils.hasText(actionName)) {
                    keys.add(AIActionNames.normalize(actionName));
                }
            }
        }
        if (decision != null && decision.intents() != null) {
            for (Intent intent : decision.intents()) {
                String actionName = resolveIntentActionName(intent);
                if (StringUtils.hasText(actionName)) {
                    keys.add(AIActionNames.normalize(actionName));
                }
            }
        }
        return keys;
    }

    private String resolveIntentActionName(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (StringUtils.hasText(intent.getAction())) {
            return intent.getAction().trim();
        }
        if (intent.getType() == IntentType.ACTION && StringUtils.hasText(intent.getIntent())) {
            return intent.getIntent().trim();
        }
        return null;
    }

    private Intent actionIntent(String actionName, Map<String, Object> params) {
        return Intent.builder()
            .type(IntentType.ACTION)
            .action(actionName)
            .actionParams(params != null ? params : Map.of())
            .confidence(1.0d)
            .build();
    }

    private Intent replyIntent(String message) {
        return Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .directAnswer(message)
            .confidence(1.0d)
            .build();
    }

    private Map<String, Object> resolveActionParams(Map<String, Object> params, List<PendingAction> stackSnapshot) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey())) {
                continue;
            }
            Object resolved = resolveTemplateValue(entry.getValue(), stackSnapshot);
            if (resolved != null) {
                out.put(entry.getKey(), resolved);
            }
        }
        return Map.copyOf(out);
    }

    private String resolveMessage(String message, List<PendingAction> stackSnapshot) {
        Object resolved = resolveTemplateValue(message, stackSnapshot);
        return resolved != null ? String.valueOf(resolved) : "";
    }

    @SuppressWarnings("unchecked")
    private Object resolveTemplateValue(Object raw, List<PendingAction> stackSnapshot) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                Object resolved = resolveTemplateValue(entry.getValue(), stackSnapshot);
                if (resolved != null) {
                    out.put(String.valueOf(entry.getKey()), resolved);
                }
            }
            return out;
        }
        if (raw instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                Object resolved = resolveTemplateValue(item, stackSnapshot);
                if (resolved != null) {
                    out.add(resolved);
                }
            }
            return out;
        }
        if (!(raw instanceof String template) || !template.contains("{{")) {
            return raw;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        if (!matcher.find()) {
            return template;
        }

        matcher.reset();
        boolean fullPlaceholder = matcher.matches();
        if (fullPlaceholder) {
            return resolveExpression(matcher.group(1), stackSnapshot);
        }

        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveExpression(matcher.group(1), stackSnapshot);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved != null ? String.valueOf(resolved) : ""));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Object resolveExpression(String expression, List<PendingAction> stackSnapshot) {
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

        Object resolved = resolvePath(path, stackSnapshot);
        if (resolved != null) {
            return resolved;
        }
        return parseFallbackLiteral(fallbackToken);
    }

    private Object resolvePath(String path, List<PendingAction> stackSnapshot) {
        if (!StringUtils.hasText(path)) {
            return null;
        }

        Map<String, Object> root = new LinkedHashMap<>();
        PendingAction pending = stackSnapshot != null && !stackSnapshot.isEmpty() ? stackSnapshot.get(0) : null;
        PendingAction previous = stackSnapshot != null && stackSnapshot.size() > 1 ? stackSnapshot.get(1) : null;
        root.put("pending", pendingToModel(pending));
        root.put("stack", Map.of("previous", pendingToModel(previous)));

        Object current = root;
        for (String token : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(token)) {
                return null;
            }
            current = map.get(token);
        }
        return current;
    }

    private Map<String, Object> pendingToModel(PendingAction pendingAction) {
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

    private Object parseFallbackLiteral(String fallbackToken) {
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

    private void applyStackPolicy(ConfirmationInterceptorStackPolicy policy, List<PendingAction> workingStack) {
        ConfirmationInterceptorStackPolicy effectivePolicy = policy != null ? policy : ConfirmationInterceptorStackPolicy.NONE;
        boolean poppedCurrent = false;
        if (effectivePolicy.popCurrent() && !workingStack.isEmpty()) {
            workingStack.remove(0);
            poppedCurrent = true;
        }
        if (!effectivePolicy.popPreviousIfActionIn().isEmpty()) {
            int previousIndex = poppedCurrent ? 0 : 1;
            if (workingStack.size() > previousIndex) {
                PendingAction previous = workingStack.get(previousIndex);
                if (previous != null && containsNormalized(effectivePolicy.popPreviousIfActionIn(), previous.action())) {
                    workingStack.remove(previousIndex);
                }
            }
        }
    }

    private PendingAction withBooleanParam(PendingAction pendingAction, String key, boolean value) {
        Map<String, Object> params = pendingAction.actionParams() != null
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

    private boolean containsNormalized(List<String> values, String candidate) {
        if (values == null || values.isEmpty() || !StringUtils.hasText(candidate)) {
            return false;
        }
        String normalizedCandidate = normalize(candidate);
        for (String value : values) {
            if (normalize(value).equals(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
