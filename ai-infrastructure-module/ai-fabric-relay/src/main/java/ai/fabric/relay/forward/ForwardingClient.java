package ai.fabric.relay.forward;

import ai.fabric.relay.config.RelayProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ForwardingClient {

    private final RelayProperties properties;
    private final HttpClient httpClient;

    public ForwardingClient(RelayProperties properties) {
        this.properties = properties;
        Duration connectTimeout = properties != null && properties.getLimits() != null
            ? properties.getLimits().getConnectTimeout()
            : Duration.ofSeconds(2);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
    }

    public ForwardingResponse execute(URI uri,
                                      String method,
                                      String jsonBody,
                                      Map<String, String> headers,
                                      Duration timeout) {
        if (uri == null) {
            throw new IllegalArgumentException("uri is required");
        }
        String m = StringUtils.hasText(method) ? method.trim().toUpperCase() : "POST";
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(effectiveTimeout);

        if ("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m)) {
            builder.method(m, HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "", StandardCharsets.UTF_8));
        } else {
            builder.method(m, HttpRequest.BodyPublishers.noBody());
        }

        Map<String, String> safeHeaders = headers != null ? new LinkedHashMap<>(headers) : Map.of();
        builder.header("Content-Type", "application/json");
        safeHeaders.forEach((key, value) -> {
            if (!StringUtils.hasText(key) || value == null) {
                return;
            }
            builder.header(key.trim(), value);
        });

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ForwardingResponse(response.statusCode(), response.body());
        } catch (Exception ex) {
            throw new ForwardingClientException(ex.getMessage(), ex);
        }
    }

    private Duration defaultTimeout() {
        int timeoutMs = properties != null && properties.getLimits() != null
            ? properties.getLimits().getDefaultTimeoutMs()
            : 5000;
        return Duration.ofMillis(Math.max(100, timeoutMs));
    }
}

