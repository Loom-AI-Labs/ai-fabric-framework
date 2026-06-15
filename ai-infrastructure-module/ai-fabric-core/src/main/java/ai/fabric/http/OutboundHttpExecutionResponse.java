package ai.fabric.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OutboundHttpExecutionResponse(
    int statusCode,
    String body,
    Map<String, List<String>> headers
) {
    public OutboundHttpExecutionResponse {
        headers = headers != null ? Map.copyOf(new LinkedHashMap<>(headers)) : Map.of();
    }
}
