package ai.fabric.provider.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProviderRegistryService
 */
class ProviderRegistryServiceTest {

    private ProviderRegistryService registry;

    @BeforeEach
    void setUp() {
        // Reset singleton for testing
        registry = ProviderRegistryService.getInstance();
    }

    @Test
    void testRegistryLoads() {
        assertNotNull(registry, "Registry service should be initialized");
    }

    @Test
    void testGetLLMProviders() {
        List<ProviderDefinition> llmProviders = registry.getLLMProviders();
        assertNotNull(llmProviders, "LLM providers list should not be null");
        assertFalse(llmProviders.isEmpty(), "Should have at least one LLM provider");
        
        // Check for expected providers
        List<String> names = llmProviders.stream()
            .map(ProviderDefinition::getName)
            .toList();
        
        assertTrue(names.contains("openai"), "Should include OpenAI");
        assertTrue(names.contains("anthropic"), "Should include Anthropic");
        assertTrue(names.contains("gemini"), "Should include Gemini");
        assertTrue(names.contains("cohere"), "Should include Cohere");
        assertTrue(names.contains("azure"), "Should include Azure");
    }

    @Test
    void testGetEmbeddingProviders() {
        List<ProviderDefinition> embeddingProviders = registry.getEmbeddingProviders();
        assertNotNull(embeddingProviders, "Embedding providers list should not be null");
        assertFalse(embeddingProviders.isEmpty(), "Should have at least one embedding provider");
        
        // Check for expected providers
        List<String> names = embeddingProviders.stream()
            .map(ProviderDefinition::getName)
            .toList();
        
        assertTrue(names.contains("onnx"), "Should include ONNX");
        assertTrue(names.contains("openai"), "Should include OpenAI");
        assertTrue(names.contains("gemini"), "Should include Gemini");
        assertTrue(names.contains("cohere"), "Should include Cohere");
        assertTrue(names.contains("azure"), "Should include Azure");
    }

    @Test
    void testGetProvider() {
        ProviderDefinition openai = registry.getProvider("openai", ProviderType.LLM);
        assertNotNull(openai, "Should find OpenAI provider");
        assertEquals("openai", openai.getName());
        assertEquals(ProviderType.LLM, openai.getType());
        assertNotNull(openai.getDisplayName());
        assertNotNull(openai.getDescription());
    }

    @Test
    void testGetProviderNotFound() {
        ProviderDefinition notFound = registry.getProvider("nonexistent", ProviderType.LLM);
        assertNull(notFound, "Should return null for non-existent provider");
    }

    @Test
    void testProviderDefinitionProperties() {
        ProviderDefinition openai = registry.getProvider("openai", ProviderType.LLM);
        assertNotNull(openai);
        
        assertEquals("OPENAI_API_KEY", openai.getApiKeyEnvVar());
        assertTrue(openai.isRequired());
        assertTrue(openai.isEnabled());
        assertNotNull(openai.getDefaultModel());
        assertNotNull(openai.getBaseUrl());
    }

    @Test
    void testAzureProviderAdditionalEnvVars() {
        ProviderDefinition azure = registry.getProvider("azure", ProviderType.LLM);
        assertNotNull(azure);
        
        List<String> requiredVars = azure.getRequiredEnvVars();
        assertTrue(requiredVars.contains("AZURE_API_KEY"));
        assertTrue(requiredVars.contains("AZURE_ENDPOINT"));
        assertTrue(requiredVars.contains("AZURE_DEPLOYMENT_NAME"));
    }

    @Test
    void testGetProviderNames() {
        List<String> llmNames = registry.getProviderNames(ProviderType.LLM);
        assertNotNull(llmNames);
        assertFalse(llmNames.isEmpty());
        assertTrue(llmNames.contains("openai"));
        
        List<String> embeddingNames = registry.getProviderNames(ProviderType.EMBEDDING);
        assertNotNull(embeddingNames);
        assertFalse(embeddingNames.isEmpty());
        assertTrue(embeddingNames.contains("onnx"));
    }

    @Test
    void testGetEnabledProviderNames() {
        List<String> enabledLLM = registry.getEnabledProviderNames(ProviderType.LLM);
        assertNotNull(enabledLLM);
        // All providers in registry should be enabled by default
        assertFalse(enabledLLM.isEmpty());
    }

    @Test
    void testGetRequiredEnvVars() {
        List<String> openaiVars = registry.getRequiredEnvVars("openai", ProviderType.LLM);
        assertNotNull(openaiVars);
        assertTrue(openaiVars.contains("OPENAI_API_KEY"));
        
        List<String> onnxVars = registry.getRequiredEnvVars("onnx", ProviderType.EMBEDDING);
        assertNotNull(onnxVars);
        // ONNX doesn't require API key
        assertTrue(onnxVars.isEmpty() || !onnxVars.contains("OPENAI_API_KEY"));
    }
}
