package ai.fabric.intent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.attachment.OrchestrationAttachment;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Context for orchestration requests.
 * Supports authenticated (userId) and anonymous (sessionId) flows.
 * At least one identifier (userId or sessionId) must be present.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationContext {

    /**
     * User ID for authenticated users.
     */
    private String userId;

    /**
     * Session ID for tracking anonymous usage.
     */
    private String sessionId;

    /**
     * Conversation identifier for multi-turn chat sessions (optional).
     *
     * <p>When present, conversation-aware pipeline steps can enrich the request with history and record turns.</p>
     */
    private String conversationId;

    /**
     * Request ID for tracing; generated if not provided.
     */
    private String requestId;

    /**
     * Client IP address.
     */
    private String ipAddress;

    /**
     * User agent string.
     */
    private String userAgent;

    /**
     * User locale for i18n handling.
     */
    private Locale locale;

    /**
     * Additional metadata (e.g., tier, device, referrer).
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Optional UI/system position hint (application-defined string).
     *
     * <p>This is treated as an input hint only; the server resolves the effective policy.</p>
     */
    private String position;

    /**
     * Optional requested orchestration mode (application-defined string).
     *
     * <p>This is treated as an input hint only; the server resolves the effective policy.</p>
     */
    private String mode;

    /**
     * Optional attachments provided by the client (e.g., UI cards/search results).
     *
     * <p>This is input-only; use {@link #attachmentsNormalized} after pipeline normalization.</p>
     */
    @Builder.Default
    private List<OrchestrationAttachment> attachments = new ArrayList<>();

    /**
     * Normalized attachment list (server-validated, bounded, scalar metadata only).
     *
     * <p>Populated by {@code AttachmentNormalizationStep}.</p>
     */
    @Builder.Default
    private List<NormalizedAttachment> attachmentsNormalized = new ArrayList<>();

    /**
     * Provider-native transient inputs for the current request.
     *
     * <p>These values may contain short-lived URLs. They are intentionally excluded from JSON
     * serialization and must not be copied into metadata, chat history, debug payloads, or logs.</p>
     */
    @JsonIgnore
    @Builder.Default
    private List<AIGenerationInputPart> transientInputParts = new ArrayList<>();

    /**
     * Server-resolved orchestration policy for this request.
     */
    private ai.fabric.intent.orchestration.policy.OrchestrationPolicy orchestrationPolicy;

    /**
     * Server-resolved capability intersection. Request bodies must not populate this field.
     */
    @JsonIgnore
    private ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile effectiveCapabilityProfile;

    /**
     * True if an authenticated userId was provided.
     */
    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }

    /**
     * True when no authenticated user is present.
     */
    public boolean isAnonymous() {
        return !isAuthenticated();
    }

    /**
     * Primary identifier (userId for authenticated, sessionId otherwise).
     */
    public String getIdentifier() {
        return isAuthenticated() ? userId : sessionId;
    }

    /**
     * True if a conversation identifier was provided.
     */
    public boolean hasConversation() {
        return conversationId != null && !conversationId.isBlank();
    }

    /**
     * Generate requestId if missing; stable prefix aids tracing.
     */
    public String getOrGenerateRequestId() {
        if (requestId == null || requestId.isBlank()) {
            requestId = "rag-" + UUID.randomUUID();
        }
        return requestId;
    }

    /**
     * Validate presence of at least one identifier.
     */
    public void validate() {
        if (!isAuthenticated() && (sessionId == null || sessionId.isBlank())) {
            throw new IllegalArgumentException(
                "OrchestrationContext must include userId (authenticated) or sessionId (anonymous)"
            );
        }
    }

    // Factory helpers

    public static OrchestrationContext forUser(String userId) {
        return OrchestrationContext.builder().userId(userId).build();
    }

    public static OrchestrationContext forSession(String sessionId) {
        return OrchestrationContext.builder().sessionId(sessionId).build();
    }

    public static OrchestrationContext anonymous() {
        return OrchestrationContext.builder()
            .sessionId("anon-" + UUID.randomUUID())
            .build();
    }

    public static OrchestrationContext forTest() {
        return OrchestrationContext.builder()
            .userId("test-user-" + UUID.randomUUID())
            .sessionId("test-session-" + UUID.randomUUID())
            .build();
    }
}
