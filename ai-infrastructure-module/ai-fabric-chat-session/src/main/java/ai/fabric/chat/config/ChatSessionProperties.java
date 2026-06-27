package ai.fabric.chat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for chat session (conversation) support.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.chat")
public class ChatSessionProperties {

    /**
     * Enables conversation enrichment and recording pipeline steps.
     */
    private boolean enabled = false;

    /**
     * Maximum number of recent turns to include as prompt context.
     */
    @Min(0)
    @Max(50)
    private int windowSize = 10;

    /**
     * Hard cap for rendered conversation history included in prompts (characters).
     */
    @Min(256)
    private int maxContextChars = 8_000;

    /**
     * When true, creates missing sessions on first use.
     */
    private boolean autoCreateSessions = true;

    /**
     * Sliding window is the only built-in strategy in this release.
     */
    private MemoryStrategy memoryStrategy = MemoryStrategy.SLIDING_WINDOW;

    /**
     * Number of turns a previously pinned target may be reused when no new attachments are provided.
     *
     * <p>Example: after selecting an attachment, follow-up messages like "summarize this" may reuse that target for the
     * next N turns even if the client does not resend attachments.</p>
     */
    @Min(0)
    @Max(10)
    private int pinnedTargetReuseWindowTurns = 3;

    /**
     * Maximum number of pending confirmation actions stored per conversation.
     *
     * <p>The newest action remains on top of the stack. When the limit is exceeded, the oldest
     * pending actions are discarded to keep session metadata bounded.</p>
     */
    @Min(1)
    @Max(20)
    private int maxPendingActionStackDepth = 8;

    /**
     * Configuration for persisting pinned targets (attachments / action result items) into conversation state.
     *
     * <p>This is short-lived conversational context intended for follow-up turns, not a long-term KB/index.</p>
     */
    private PinnedTargetPersistence pinnedTargetPersistence = new PinnedTargetPersistence();

    @Data
    public static class PinnedTargetPersistence {

        /**
         * Enables persistence of pinned targets into conversation session metadata.
         */
        private boolean enabled = true;

        /**
         * Maximum number of pinned targets to persist per conversation (replaces previous list).
         */
        @Min(1)
        @Max(50)
        private int maxTargets = 8;

        /**
         * Maximum number of characters to persist for each target contentText.
         *
         * <p>0 means "no additional truncation" at persistence time (attachment normalization may still truncate).</p>
         */
        @Min(0)
        private int maxContentChars = 0;

        /**
         * Maximum number of metadata entries to persist per target.
         */
        @Min(0)
        @Max(50)
        private int maxMetadataEntries = 12;

        /**
         * Maximum number of characters allowed for each metadata value.
         */
        @Min(0)
        private int maxMetadataValueChars = 120;

        /**
         * When true, targets without an explicit id may still be persisted (contentText/metadata only).
         */
        private boolean storeIdlessTargets = true;
    }

    public enum MemoryStrategy {
        SLIDING_WINDOW
    }
}
