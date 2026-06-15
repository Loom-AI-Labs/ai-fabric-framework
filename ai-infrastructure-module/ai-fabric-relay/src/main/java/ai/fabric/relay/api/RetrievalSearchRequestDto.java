package ai.fabric.relay.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record RetrievalSearchRequestDto(
    String query,
    String vectorSpace,
    Integer topK,
    String cursor,
    Map<String, Object> filters,
    TraceContextDto trace
) {
}

