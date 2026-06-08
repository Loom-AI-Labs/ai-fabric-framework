package com.ai.fabric.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = ProviderOnlyStarterContextTest.TestApplication.class,
    properties = {
        "ai.enabled=true",

        // Provider-only: no embeddings/search/vector DB required to boot.
        "ai.service.features.enable-search=false",
        "ai.service.features.enable-embeddings=false",

        // Keep generation enabled but satisfy config validator with dummy values.
        "ai.providers.llm-provider=openai",
        "ai.providers.openai.api-key=sk-test-key",
        "ai.providers.openai.base-url=https://api.openai.com/v1",
        "ai.providers.openai.model=gpt-4o-mini",

        // Storage strategy auto-config uses JDBC; provide an embedded datasource.
        "spring.datasource.url=jdbc:h2:mem:provider_only;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
    }
)
class ProviderOnlyStarterContextTest {

    @SpringBootApplication
    static class TestApplication {}

    @Test
    void contextLoads() {
    }
}

