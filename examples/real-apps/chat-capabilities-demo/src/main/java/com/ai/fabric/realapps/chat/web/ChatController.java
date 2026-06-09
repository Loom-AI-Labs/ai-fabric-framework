package com.ai.fabric.realapps.chat.web;

import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.chat.domain.ChatSession;
import com.ai.infrastructure.chat.domain.ChatTurn;
import com.ai.infrastructure.chat.service.ChatSessionService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.attachment.OrchestrationAttachment;
import com.ai.infrastructure.intent.orchestration.RAGOrchestrator;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.hibernate.Hibernate;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final String CONVERSATION_PREFIX = "chat-";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRINGS = new TypeReference<>() {
    };

    private static final String SUGGESTIONS_SYSTEM_PROMPT_TEMPLATE = """
        You generate short, clickable UI suggestions for a user.
        Output MUST be valid JSON: an array of strings.
        Return exactly %d suggestions (no more, no less).
        Each suggestion should be a short question or task the user can select.
        Do not include explanations.
        """;

    private final ObjectProvider<RAGOrchestrator> orchestratorProvider;
    private final ObjectProvider<ChatSessionService> chatSessionServiceProvider;
    private final ObjectProvider<AICoreService> aiCoreServiceProvider;
    private final ObjectProvider<AIActionRegistry> aiActionRegistryProvider;

    @PostMapping("/query")
    public ResponseEntity<ChatQueryResponse> query(@Valid @RequestBody ChatQueryRequest request) {
        RAGOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            return ResponseEntity.ok(ChatQueryResponse.builder()
                .success(false)
                .message("Orchestrator not configured")
                .build());
        }

        String conversationId = StringUtils.hasText(request.getConversationId())
            ? request.getConversationId()
            : CONVERSATION_PREFIX + UUID.randomUUID();

        OrchestrationContext context = buildContext(request, conversationId);
        OrchestrationResult result = orchestrator.orchestrate(request.getQuery(), context);

        return ResponseEntity.ok(ChatQueryResponse.builder()
            .success(true)
            .conversationId(conversationId)
            .userId(context.getUserId())
            .sessionId(context.getSessionId())
            .result(result)
            .build());
    }

    /**
     * Lightweight suggestion endpoint (no orchestration / no retrieval).
     *
     * <p>Input: arbitrary user-provided context. Output: JSON array of suggested next questions/tasks.</p>
     */
    @PostMapping("/suggestions")
    public ResponseEntity<SuggestionsResponse> suggestions(@Valid @RequestBody SuggestionsRequest request) {
        int n = request.getMaxSuggestions() != null ? request.getMaxSuggestions() : 5;
        n = Math.max(1, Math.min(n, 10));

        AIActionRegistry registry = aiActionRegistryProvider != null ? aiActionRegistryProvider.getIfAvailable() : null;
        List<AIActionMetaData> actions = registry != null ? registry.getAllMetadata() : List.of();
        List<OrchestrationAttachment> attachments = request.getAttachments() != null ? request.getAttachments() : List.of();

        String prompt = buildActionAwareSuggestionsPrompt(request.getContent(), actions, attachments, n);

        AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
        if (aiCoreService == null) {
            return ResponseEntity.ok(SuggestionsResponse.builder()
                .success(true)
                .message("AI provider not configured; returning fallback suggestions")
                .suggestions(buildFallbackSuggestions(request.getContent(), actions, attachments, n))
                .raw(null)
                .build());
        }

        try {
            AIGenerationResponse response = aiCoreService.generateContent(AIGenerationRequest.builder()
                .entityType("suggestions")
                .entityId("adhoc")
                .generationType("suggestions")
                .systemPrompt(SUGGESTIONS_SYSTEM_PROMPT_TEMPLATE.formatted(n))
                .prompt(prompt)
                .maxTokens(300)
                .temperature(0.4)
                .authContext(buildGenerationAuthContext(request.getUserId()))
                .build(), LlmPurpose.GENERATION);

            String raw = response != null ? response.getContent() : null;
            List<String> suggestions = normalizeSuggestions(parseSuggestions(raw), n);

            if (suggestions.isEmpty()) {
                suggestions = buildFallbackSuggestions(request.getContent(), actions, attachments, n);
            }

            return ResponseEntity.ok(SuggestionsResponse.builder()
                .success(true)
                .message(null)
                .suggestions(suggestions)
                .raw(raw)
                .build());
        } catch (Exception ex) {
            return ResponseEntity.ok(SuggestionsResponse.builder()
                .success(true)
                .message("AI suggestions unavailable; returning fallback suggestions")
                .suggestions(buildFallbackSuggestions(request.getContent(), actions, attachments, n))
                .raw(null)
                .build());
        }
    }

    private AIAccessSubjectContext buildGenerationAuthContext(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        return AIAccessSubjectContext.builder()
            .subjectId(userId.trim())
            .subjectType("END_USER")
            .build();
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ConversationResponse> getConversation(@PathVariable String conversationId,
                                                                @RequestParam(value = "userId", required = false) String userId,
                                                                @RequestParam(value = "ownerId", required = false) String ownerId) {
        ChatSessionService service = chatSessionServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.notFound().build();
        }
        String resolvedOwnerId = resolveOwnerId(userId, ownerId);
        if (!StringUtils.hasText(resolvedOwnerId)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(toConversationResponse(service.getSession(conversationId, resolvedOwnerId)));
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationSummaryResponse>> listConversations(
        @RequestParam(value = "userId", required = false) String userId,
        @RequestParam(value = "ownerId", required = false) String ownerId
    ) {
        ChatSessionService service = chatSessionServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.ok(List.of());
        }
        String resolvedOwnerId = resolveOwnerId(userId, ownerId);
        if (!StringUtils.hasText(resolvedOwnerId)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.getUserConversations(resolvedOwnerId).stream().map(this::toConversationSummaryResponse).toList());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId,
                                                   @RequestParam(value = "userId", required = false) String userId,
                                                   @RequestParam(value = "ownerId", required = false) String ownerId) {
        ChatSessionService service = chatSessionServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.noContent().build();
        }
        String resolvedOwnerId = resolveOwnerId(userId, ownerId);
        if (!StringUtils.hasText(resolvedOwnerId)) {
            return ResponseEntity.badRequest().build();
        }
        service.deleteConversation(conversationId, resolvedOwnerId);
        return ResponseEntity.noContent().build();
    }

    private OrchestrationContext buildContext(ChatQueryRequest request, String conversationId) {
        String userId = StringUtils.hasText(request.getUserId()) ? request.getUserId() : null;
        String sessionId = StringUtils.hasText(request.getSessionId())
            ? request.getSessionId()
            : "anon-" + UUID.randomUUID();

        OrchestrationContext.OrchestrationContextBuilder builder = OrchestrationContext.builder()
            .conversationId(conversationId);

        if (StringUtils.hasText(request.getPosition())) {
            builder.position(request.getPosition());
        }
        if (StringUtils.hasText(request.getMode())) {
            builder.mode(request.getMode());
        }

        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            builder.attachments(request.getAttachments());
        }

        if (StringUtils.hasText(userId)) {
            builder.userId(userId);
        }
        builder.sessionId(sessionId);

        return builder.build();
    }

    private String resolveOwnerId(String userId, String ownerId) {
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        if (StringUtils.hasText(ownerId)) {
            return ownerId;
        }
        return null;
    }

    private String buildSuggestionsPrompt(String content, int n) {
        return """
            User attached these items/context:
            %s

            Suggest %d suggestions/questions for the user to select next.

            Output format: JSON array of %d strings.
            """.formatted(content, n, n);
    }

    private String buildActionAwareSuggestionsPrompt(String content,
                                                    List<AIActionMetaData> actions,
                                                    List<OrchestrationAttachment> attachments,
                                                    int n) {
        String availableActions = formatActions(actions);
        String attachedItems = formatAttachments(attachments);

        return """
            Task:
            Give me most suitable %d suggestions (questions/actions) based on the available actions and attached items.

            Output MUST be valid JSON: an array of strings.
            Return exactly %d suggestions (no more, no less).
            Each suggestion should be short, clickable, and phrased as a user request.
            Prefer suggestions that map to one of the available actions.
            If attachments are present, ground suggestions in them.
            Do not include explanations.

            User context (optional):
            %s

            Attached items (may be empty):
            %s

            Available actions (may be empty):
            %s
            """.formatted(n, n,
            StringUtils.hasText(content) ? content.trim() : "(none)",
            attachedItems,
            availableActions);
    }

    private String formatActions(List<AIActionMetaData> actions) {
        if (actions == null || actions.isEmpty()) {
            return "(none)";
        }
        return actions.stream()
            .filter(a -> a != null && StringUtils.hasText(a.getName()))
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .limit(60)
            .map(a -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", a.getName());
                out.put("category", a.getCategory());
                out.put("description", a.getDescription());
                out.put("accessMode", a.getAccessMode() != null ? a.getAccessMode().name() : null);
                out.put("requiredParameters", a.getRequiredParameters());
                out.put("parameters", a.getParameters());
                try {
                    return OBJECT_MAPPER.writeValueAsString(out);
                } catch (Exception ex) {
                    return out.toString();
                }
            })
            .collect(Collectors.joining("\n"));
    }

    private String formatAttachments(List<OrchestrationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "(none)";
        }
        return attachments.stream()
            .filter(a -> a != null)
            .limit(20)
            .map(a -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", a.getId());
                out.put("vectorSpace", a.getVectorSpace());
                out.put("contentText", a.getContentText());
                out.put("metadata", a.getMetadata());
                out.put("source", a.getSource());
                out.put("url", a.getUrl());
                out.put("imageUrl", a.getImageUrl());
                try {
                    return OBJECT_MAPPER.writeValueAsString(out);
                } catch (Exception ex) {
                    return out.toString();
                }
            })
            .collect(Collectors.joining("\n"));
    }

    private List<String> buildFallbackSuggestions(String content,
                                                 List<AIActionMetaData> actions,
                                                 List<OrchestrationAttachment> attachments,
                                                 int n) {
        String hint = extractHint(content, attachments);
        java.util.Set<String> actionNames = actions != null
            ? actions.stream()
            .filter(a -> a != null && StringUtils.hasText(a.getName()))
            .map(a -> a.getName().toLowerCase())
            .collect(Collectors.toSet())
            : java.util.Set.of();

        List<String> out = new ArrayList<>(n);

        if (containsAny(actionNames, "list_products", "search_products")) {
            out.add("Show me products related to " + hint);
        }
        if (containsAny(actionNames, "list_products")) {
            out.add("Show me more options for " + hint);
        }
        if (containsAny(actionNames, "list_products", "search_products")) {
            out.add("Compare the top options for " + hint);
        }
        if (containsAny(actionNames, "list_orders", "get_active_orders", "get_orders", "list_my_orders")) {
            out.add("Show my recent orders");
        }
        if (containsAny(actionNames, "list_my_addresses", "list_addresses")) {
            out.add("Show my saved addresses");
        }

        if (out.isEmpty()) {
            out.add("What can you do with these attached items?");
            out.add("Summarize the attached items");
            out.add("What are the next best actions?");
        }

        // Ensure exactly n suggestions (dedupe + trim/pad).
        List<String> deduped = out.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();

        List<String> normalized = new ArrayList<>(Math.min(n, deduped.size()));
        for (String s : deduped) {
            if (normalized.size() >= n) {
                break;
            }
            normalized.add(s);
        }
        while (normalized.size() < n) {
            normalized.add("Tell me more about " + hint);
        }
        return normalized;
    }

    private boolean containsAny(java.util.Set<String> haystack, String... needles) {
        if (haystack == null || haystack.isEmpty() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (!StringUtils.hasText(needle)) {
                continue;
            }
            if (haystack.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String extractHint(String content, List<OrchestrationAttachment> attachments) {
        String raw = StringUtils.hasText(content) ? content.trim() : null;
        if (!StringUtils.hasText(raw)) {
            raw = firstAttachmentText(attachments);
        }
        if (!StringUtils.hasText(raw)) {
            return "your request";
        }
        String trimmed = raw.replaceAll("\\s+", " ").trim();
        if (trimmed.length() > 60) {
            trimmed = trimmed.substring(0, 60).trim();
        }
        return "\"" + trimmed + "\"";
    }

    private String firstAttachmentText(List<OrchestrationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        for (OrchestrationAttachment a : attachments) {
            if (a == null) {
                continue;
            }
            if (StringUtils.hasText(a.getContentText())) {
                return a.getContentText();
            }
            if (StringUtils.hasText(a.getUrl())) {
                return a.getUrl();
            }
            if (StringUtils.hasText(a.getVectorSpace())) {
                return a.getVectorSpace();
            }
        }
        return null;
    }

    private List<String> parseSuggestions(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String trimmed = raw.trim();

        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            String candidate = trimmed.substring(start, end + 1);
            try {
                List<String> parsed = OBJECT_MAPPER.readValue(candidate, LIST_OF_STRINGS);
                return parsed != null ? parsed : List.of();
            } catch (Exception ignored) {
            }
        }

        List<String> out = new ArrayList<>();
        for (String line : trimmed.split("\\R")) {
            String normalized = line.replaceFirst("^\\s*[-*\\d.]+\\s*", "").trim();
            if (StringUtils.hasText(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    private List<String> normalizeSuggestions(List<String> suggestions, int n) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Math.min(n, suggestions.size()));
        for (String suggestion : suggestions) {
            if (!StringUtils.hasText(suggestion)) {
                continue;
            }
            String trimmed = suggestion.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(trimmed);
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    @Data
    public static class ChatQueryRequest {
        @NotBlank
        private String query;
        private String userId;
        private String sessionId;
        private String conversationId;
        private String position;
        private String mode;
        private List<OrchestrationAttachment> attachments;
    }

    @Data
    @Builder
    public static class ChatQueryResponse {
        private boolean success;
        private String message;
        private String conversationId;
        private String userId;
        private String sessionId;
        private OrchestrationResult result;
    }

    @Data
    @Builder
    public static class ConversationSummaryResponse {
        private String id;
        private String ownerId;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime lastInteractionAt;
        private int turnsCount;
    }

    @Data
    @Builder
    public static class ConversationResponse {
        private String id;
        private String ownerId;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime lastInteractionAt;
        private List<TurnResponse> turns;
    }

    @Data
    @Builder
    public static class TurnResponse {
        private LocalDateTime timestamp;
        private String userQuery;
        private String aiResponse;
    }

    @Data
    public static class SuggestionsRequest {
        private String content;
        private String userId;
        private List<OrchestrationAttachment> attachments;
        @Min(1)
        @Max(10)
        private Integer maxSuggestions = 5;
    }

    @Data
    @Builder
    public static class SuggestionsResponse {
        private boolean success;
        private String message;
        private List<String> suggestions;
        private String raw;
    }

    private ConversationResponse toConversationResponse(ChatSession session) {
        if (session == null) {
            return null;
        }
        List<ChatTurn> turns = (session.getTurns() != null && Hibernate.isInitialized(session.getTurns()))
            ? session.getTurns()
            : List.of();
        List<TurnResponse> mappedTurns = turns.stream()
            .filter(t -> t != null)
            .map(t -> TurnResponse.builder()
                .timestamp(t.getTimestamp())
                .userQuery(t.getUserQuery())
                .aiResponse(t.getAiResponse())
                .build())
            .toList();
        return ConversationResponse.builder()
            .id(session.getId())
            .ownerId(session.getOwnerId())
            .status(session.getStatus() != null ? session.getStatus().name() : null)
            .createdAt(session.getCreatedAt())
            .lastInteractionAt(session.getLastInteractionAt())
            .turns(mappedTurns)
            .build();
    }

    private ConversationSummaryResponse toConversationSummaryResponse(ChatSession session) {
        if (session == null) {
            return null;
        }
        int turnsCount = (session.getTurns() != null && Hibernate.isInitialized(session.getTurns()))
            ? session.getTurns().size()
            : 0;
        return ConversationSummaryResponse.builder()
            .id(session.getId())
            .ownerId(session.getOwnerId())
            .status(session.getStatus() != null ? session.getStatus().name() : null)
            .createdAt(session.getCreatedAt())
            .lastInteractionAt(session.getLastInteractionAt())
            .turnsCount(turnsCount)
            .build();
    }
}
