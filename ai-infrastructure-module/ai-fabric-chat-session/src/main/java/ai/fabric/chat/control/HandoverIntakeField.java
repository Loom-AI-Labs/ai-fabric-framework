package ai.fabric.chat.control;

import java.util.List;
import org.springframework.util.StringUtils;

/** Safe public description of one server-owned handover intake field. */
public record HandoverIntakeField(
    String key,
    String label,
    HandoverIntakeFieldType type,
    boolean required,
    int maxLength,
    String purpose,
    boolean consentRequired,
    List<String> options
) {
    public HandoverIntakeField {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(label) || type == null) {
            throw new IllegalArgumentException("key, label and type are required");
        }
        if (maxLength < 0 || maxLength > 4096) {
            throw new IllegalArgumentException("maxLength must be between 0 and 4096");
        }
        key = key.trim();
        label = label.trim();
        purpose = StringUtils.hasText(purpose) ? purpose.trim() : null;
        options = options == null ? List.of() : options.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }
}
