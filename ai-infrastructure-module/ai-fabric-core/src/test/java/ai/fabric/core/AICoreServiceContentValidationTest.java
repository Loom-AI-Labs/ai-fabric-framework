package ai.fabric.core;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProviderManager;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplate;
import ai.fabric.prompt.PromptTemplateKey;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.prompt.ResolvedPromptTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AICoreServiceContentValidationTest {

    @Test
    void validateContentParsesStructuredJsonResult() {
        AIProviderManager providerManager = providerReturning("""
            {
              "valid": false,
              "errors": ["missing title", {"field":"body","message":"too short"}],
              "suggestions": ["Add a title"],
              "confidence": 0.82
            }
            """);
        AICoreService coreService = coreService(providerManager);

        Map<String, Object> result = coreService.validateContent(
            "short body",
            Map.of("title", "required")
        );

        assertThat(result)
            .containsEntry("valid", false)
            .containsEntry("confidence", 0.82);
        assertThat(result.get("errors"))
            .asList()
            .contains("missing title", "{\"field\":\"body\",\"message\":\"too short\"}");
        assertThat(result.get("suggestions")).asList().containsExactly("Add a title");
    }

    @Test
    void validateContentParsesFencedJsonResult() {
        AIProviderManager providerManager = providerReturning("""
            The validation result is:
            ```json
            {"valid": true, "errors": [], "suggestions": ["Looks ready"]}
            ```
            """);
        AICoreService coreService = coreService(providerManager);

        Map<String, Object> result = coreService.validateContent("ready", Map.of());

        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("errors")).asList().isEmpty();
        assertThat(result.get("suggestions")).asList().containsExactly("Looks ready");
    }

    @Test
    void validateContentFailsClosedForMalformedJsonResult() {
        AIProviderManager providerManager = providerReturning("valid: probably");
        AICoreService coreService = coreService(providerManager);

        Map<String, Object> result = coreService.validateContent("content", Map.of());

        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("errors")).asList().containsExactly("Failed to parse AI validation JSON");
        assertThat(result.get("suggestions")).asList()
            .containsExactly("Review the content manually because AI validation returned malformed JSON.");
    }

    @Test
    void validateContentUsesPurposeDefaultsAndRenderedPrompts() {
        AIProviderManager providerManager = providerReturning("""
            {"valid": true, "errors": [], "suggestions": []}
            """);
        AICoreService coreService = coreService(providerManager);

        coreService.validateContent("content", Map.of("tone", "formal"));

        verify(providerManager).generateContent(any(AIGenerationRequest.class), eq("openai"));
    }

    private static AIProviderManager providerReturning(String content) {
        AIProviderManager providerManager = mock(AIProviderManager.class);
        when(providerManager.generateContent(any(AIGenerationRequest.class), eq("openai")))
            .thenReturn(AIGenerationResponse.builder()
                .content(content)
                .model("gpt-4o-mini")
                .build());
        return providerManager;
    }

    private static AICoreService coreService(AIProviderManager providerManager) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AIEmbeddingService> embeddingProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AISearchService> searchProvider = mock(ObjectProvider.class);

        return new AICoreService(
            new AIProviderConfig(),
            providerManager,
            embeddingProvider,
            searchProvider,
            promptResolver(),
            new PromptRenderer()
        );
    }

    private static PromptTemplateResolver promptResolver() {
        PromptTemplateResolver resolver = mock(PromptTemplateResolver.class);
        when(resolver.resolve("core/content-validation", "system"))
            .thenReturn(new ResolvedPromptTemplate(
                new PromptTemplate(
                    new PromptTemplateKey("core/content-validation", "v1", "system"),
                    "You are a validator."
                ),
                List.of("v1")
            ));
        when(resolver.resolve("core/content-validation", "user"))
            .thenReturn(new ResolvedPromptTemplate(
                new PromptTemplate(
                    new PromptTemplateKey("core/content-validation", "v1", "user"),
                    "Content: {{content}}\nRules: {{validation_rules}}"
                ),
                List.of("v1")
            ));
        return resolver;
    }
}
