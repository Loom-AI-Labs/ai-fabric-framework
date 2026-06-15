package ai.fabric.http;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

public interface HttpClient {

    <T> ResponseEntity<T> exchange(
        String url,
        HttpMethod method,
        HttpEntity<?> requestEntity,
        Class<T> responseType
    );
}

