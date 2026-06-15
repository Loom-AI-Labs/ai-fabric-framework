package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-native input part used for transient inputs such as short-lived file URLs.
 *
 * <p>FILE_URL values are intentionally transient. They must not be persisted in chat history,
 * debug payloads, vector indexes, or logs.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AIGenerationInputPart {

    private AIGenerationInputType type;

    private String text;

    private String url;

    private String documentId;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    private String expiresAt;

    public boolean isFileUrl() {
        return AIGenerationInputType.FILE_URL.equals(type) && StringUtils.hasText(url);
    }

    public Map<String, Object> redactedDescriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", type != null ? type.name() : null);
        if (StringUtils.hasText(documentId)) {
            descriptor.put("documentId", documentId.trim());
        }
        if (StringUtils.hasText(fileName)) {
            descriptor.put("fileName", fileName.trim());
        }
        if (StringUtils.hasText(contentType)) {
            descriptor.put("contentType", contentType.trim());
        }
        if (sizeBytes != null) {
            descriptor.put("sizeBytes", sizeBytes);
        }
        if (StringUtils.hasText(expiresAt)) {
            descriptor.put("expiresAt", expiresAt.trim());
        }
        if (isFileUrl()) {
            descriptor.put("url", "[REDACTED_TRANSIENT_FILE_URL]");
        }
        return descriptor;
    }
}
