package ai.fabric.http;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultOutboundHttpExecutor implements OutboundHttpExecutor {

    private final AIHttpClientFactory httpClientFactory;

    public DefaultOutboundHttpExecutor(AIHttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    @Override
    public OutboundHttpExecutionResponse execute(OutboundHttpExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.url())) {
            throw new IllegalArgumentException("Outbound HTTP request URL is required.");
        }
        if (request.method() == null) {
            throw new IllegalArgumentException("Outbound HTTP request method is required.");
        }

        HttpHeaders headers = new HttpHeaders();
        request.headers().forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                headers.set(key.trim(), value);
            }
        });
        HttpEntity<String> entity = new HttpEntity<>(request.body(), headers);
        HttpClient client = request.connectTimeout() != null || request.readTimeout() != null
            ? httpClientFactory.create(request.connectTimeout(), request.readTimeout())
            : httpClientFactory.create();
        ResponseEntity<String> response = client.exchange(request.url(), request.method(), entity, String.class);
        return new OutboundHttpExecutionResponse(
            response != null ? response.getStatusCode().value() : 0,
            response != null ? response.getBody() : null,
            response != null ? copyHeaders(response.getHeaders()) : Map.of()
        );
    }

    private Map<String, List<String>> copyHeaders(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copied = new LinkedHashMap<>();
        headers.forEach((key, values) -> copied.put(key, values != null ? List.copyOf(values) : List.of()));
        return Map.copyOf(copied);
    }
}
