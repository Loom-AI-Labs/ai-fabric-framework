package ai.fabric.chat.control;

import org.springframework.util.StringUtils;

/** Typed fail-closed conversation authority violation. */
public final class ConversationControlViolationException extends RuntimeException {

    private final String code;

    public ConversationControlViolationException(String code, String message) {
        super(StringUtils.hasText(message) ? message : "Conversation control rejected the operation.");
        this.code = StringUtils.hasText(code) ? code.trim() : "CONVERSATION_CONTROL_DENIED";
    }

    public String code() {
        return code;
    }
}
