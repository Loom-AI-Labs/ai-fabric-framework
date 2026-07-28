package ai.fabric.execution.integration.chat;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.privacy.pii.PIIDetectionService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

/**
 * Chat-session adapter that persists only gateway-validated specialist turns.
 */
public final class ChatSessionAIExecutionConversationRecorder
    implements AIExecutionConversationRecorder {

    private final ChatSessionService chatSessionService;
    private final ObjectProvider<PIIDetectionService> piiDetectionService;

    public ChatSessionAIExecutionConversationRecorder(
        ChatSessionService chatSessionService,
        ObjectProvider<PIIDetectionService> piiDetectionService
    ) {
        this.chatSessionService = Objects.requireNonNull(
            chatSessionService,
            "chatSessionService is required"
        );
        this.piiDetectionService = piiDetectionService;
    }

    @Override
    public void record(
        ConversationBinding binding,
        String userInput,
        String assistantOutput,
        Map<String, Object> metadata
    ) {
        Objects.requireNonNull(binding, "binding is required");
        if (!StringUtils.hasText(userInput)) {
            throw new IllegalArgumentException("userInput is required");
        }
        if (!StringUtils.hasText(assistantOutput)) {
            throw new IllegalArgumentException("assistantOutput is required");
        }
        chatSessionService.recordTurn(
            binding.conversationId(),
            binding.userId(),
            redactForPersistence(userInput),
            redactForPersistence(assistantOutput),
            metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }

    private String redactForPersistence(String value) {
        PIIDetectionService service =
            piiDetectionService != null ? piiDetectionService.getIfAvailable() : null;
        if (service == null) {
            return value;
        }

        PIIDetectionResult analysis;
        try {
            analysis = service.analyze(value);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "PII analysis failed; the conversation turn was not persisted.",
                ex
            );
        }
        if (analysis == null || !analysis.isPiiDetected()) {
            return value;
        }
        if (StringUtils.hasText(analysis.getProcessedQuery())
            && !analysis.getProcessedQuery().equals(value)) {
            return analysis.getProcessedQuery();
        }
        return redact(value, analysis.getDetections());
    }

    private String redact(String original, List<PIIDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            throw new IllegalStateException(
                "PII was detected but no safe persisted representation was produced."
            );
        }
        StringBuilder builder = new StringBuilder(original);
        detections.stream()
            .filter(detection ->
                detection != null && StringUtils.hasText(detection.getMaskedValue())
            )
            .sorted(
                Comparator.comparingInt(PIIDetection::getStartIndex).reversed()
            )
            .forEach(detection -> {
                int start = Math.max(
                    0,
                    Math.min(detection.getStartIndex(), builder.length())
                );
                int end = Math.max(
                    start,
                    Math.min(detection.getEndIndex(), builder.length())
                );
                builder.replace(start, end, detection.getMaskedValue());
            });
        String redacted = builder.toString();
        if (redacted.equals(original)) {
            throw new IllegalStateException(
                "PII was detected but could not be redacted for persistence."
            );
        }
        return redacted;
    }
}
