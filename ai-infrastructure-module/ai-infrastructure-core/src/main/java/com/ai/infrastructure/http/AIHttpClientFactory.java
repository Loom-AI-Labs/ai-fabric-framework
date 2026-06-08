package com.ai.infrastructure.http;

import java.time.Duration;

public interface AIHttpClientFactory {

    HttpClient create();

    HttpClient create(Duration connectTimeout, Duration readTimeout);
}

