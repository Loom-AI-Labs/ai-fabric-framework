package ai.fabric.http;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

public class DefaultAIHttpClientFactory implements AIHttpClientFactory {

    private final RestTemplateBuilder restTemplateBuilder;
    private final AIHttpClientProperties properties;

    public DefaultAIHttpClientFactory(RestTemplateBuilder restTemplateBuilder, AIHttpClientProperties properties) {
        this.restTemplateBuilder = Objects.requireNonNull(restTemplateBuilder, "restTemplateBuilder");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public HttpClient create() {
        return create(properties.getConnectTimeout(), properties.getReadTimeout());
    }

    @Override
    public HttpClient create(Duration connectTimeout, Duration readTimeout) {
        RestTemplateBuilder builder = restTemplateBuilder;
        if (connectTimeout != null) {
            builder = builder.setConnectTimeout(connectTimeout);
        }
        if (readTimeout != null) {
            builder = builder.setReadTimeout(readTimeout);
        }
        RestTemplate restTemplate = builder.build();
        return new RestTemplateHttpClient(restTemplate);
    }
}

