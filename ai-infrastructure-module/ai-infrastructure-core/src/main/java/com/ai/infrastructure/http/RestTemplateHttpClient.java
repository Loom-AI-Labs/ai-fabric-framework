package com.ai.infrastructure.http;

import java.util.Objects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class RestTemplateHttpClient implements HttpClient {

    private final RestTemplate restTemplate;

    public RestTemplateHttpClient(RestTemplate restTemplate) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
        return restTemplate.exchange(url, method, requestEntity, responseType);
    }
}

