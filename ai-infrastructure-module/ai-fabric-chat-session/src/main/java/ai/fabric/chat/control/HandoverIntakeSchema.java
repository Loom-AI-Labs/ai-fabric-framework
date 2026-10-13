package ai.fabric.chat.control;

import java.util.List;

/** Reviewed intake schema associated with a conversation-channel binding. */
public record HandoverIntakeSchema(
    String schemaVersion,
    boolean anonymousAllowed,
    List<HandoverIntakeField> fields
) {
    public HandoverIntakeSchema {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? "HANDOVER_INTAKE_V1"
            : schemaVersion.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.size() > 16) {
            throw new IllegalArgumentException("handover intake supports at most 16 fields");
        }
    }
}
