package com.subscription.hub.ai;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adds bounded UI chat history to the Account Resolver pipeline without changing AI Fabric core.
 */
@Component
public class ResolverChatHistoryEnrichmentStep implements PipelineStep {

    public static final String METADATA_KEY = "resolverChatHistory";

    private static final String STEP_NAME = "ResolverChatHistoryEnrichment";
    private static final int STEP_ORDER = 25;
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_MESSAGE_CHARS = 700;
    private static final String METADATA_TURNS = "resolverChatHistoryTurns";

    @Override
    public PipelineContext process(PipelineContext context) {
        if (context == null || context.isShouldTerminate()) {
            return context;
        }

        OrchestrationContext orchestrationContext = context.getOrchestrationContext();
        if (orchestrationContext == null || orchestrationContext.getMetadata() == null) {
            return context;
        }

        List<AIChatMessage> incomingHistory = extractHistory(orchestrationContext.getMetadata().get(METADATA_KEY));
        if (incomingHistory.isEmpty()) {
            return context;
        }

        List<AIChatMessage> mergedHistory = new ArrayList<>();
        if (context.getHistoryMessages() != null) {
            mergedHistory.addAll(context.getHistoryMessages());
        }
        mergedHistory.addAll(incomingHistory);
        mergedHistory = tail(mergedHistory, MAX_HISTORY_MESSAGES);

        Map<String, Object> metadata = new LinkedHashMap<>(context.getMetadata());
        metadata.put(METADATA_TURNS, mergedHistory.size());

        return context.toBuilder()
            .historyMessages(List.copyOf(mergedHistory))
            .metadata(metadata)
            .build();
    }

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    private static List<AIChatMessage> extractHistory(Object rawHistory) {
        if (!(rawHistory instanceof List<?> rawMessages) || rawMessages.isEmpty()) {
            return List.of();
        }

        List<AIChatMessage> messages = new ArrayList<>();
        for (Object rawMessage : rawMessages) {
            AIChatMessage message = toChatMessage(rawMessage);
            if (message != null) {
                messages.add(message);
            }
        }
        return tail(messages, MAX_HISTORY_MESSAGES);
    }

    private static AIChatMessage toChatMessage(Object rawMessage) {
        if (rawMessage instanceof AIChatMessage chatMessage) {
            return normalize(chatMessage.getRole(), chatMessage.getContent());
        }
        if (!(rawMessage instanceof Map<?, ?> messageMap)) {
            return null;
        }

        AIChatRole role = parseRole(messageMap.get("role"));
        String content = valueAsString(messageMap.get("content"));
        return normalize(role, content);
    }

    private static AIChatMessage normalize(AIChatRole role, String content) {
        if (role != AIChatRole.USER && role != AIChatRole.ASSISTANT) {
            return null;
        }

        String normalizedContent = truncate(collapseWhitespace(content), MAX_MESSAGE_CHARS);
        if (normalizedContent.isBlank()) {
            return null;
        }

        return AIChatMessage.builder()
            .role(role)
            .content(normalizedContent)
            .build();
    }

    private static AIChatRole parseRole(Object rawRole) {
        String role = valueAsString(rawRole).trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "user" -> AIChatRole.USER;
            case "assistant" -> AIChatRole.ASSISTANT;
            default -> null;
        };
    }

    private static String valueAsString(Object value) {
        return value instanceof String string ? string : "";
    }

    private static String collapseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static <T> List<T> tail(List<T> values, int maxItems) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() <= maxItems) {
            return values;
        }
        return values.subList(values.size() - maxItems, values.size());
    }
}
