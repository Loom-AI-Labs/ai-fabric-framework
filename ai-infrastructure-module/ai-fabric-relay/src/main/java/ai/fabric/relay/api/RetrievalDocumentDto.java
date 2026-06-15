package ai.fabric.relay.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record RetrievalDocumentDto(
    String id,
    String content,
    Float score,
    String source,
    String url,
    String vectorSpace,
    Map<String, String> metadata
) {
}

