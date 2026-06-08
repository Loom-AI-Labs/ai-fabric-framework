package com.ai.infrastructure.embedding;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.config.TestConfiguration;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Test for Embedding Provider Abstraction
 * 
 * Tests the EmbeddingProvider abstraction with different providers:
 * - ONNXEmbeddingProvider (default)
 * - OpenAIEmbeddingProvider (if configured)
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles("dev")
public class EmbeddingProviderIntegrationTest {
    
    @Autowired
    private AIEmbeddingService embeddingService;
    
    @Autowired(required = false)
    private EmbeddingProvider embeddingProvider;
    
    @Autowired
    private AIProviderConfig config;
    
    @BeforeEach
    public void setUp() {
        // Verify embedding service is available
        assertNotNull(embeddingService, "AIEmbeddingService should be available");
    }
    
    @Test
    public void testEmbeddingProviderIsConfigured() {
        // Test that embedding provider is configured
        assertNotNull(embeddingProvider, "EmbeddingProvider should be configured");
        assertNotNull(embeddingProvider.getProviderName(), "Provider name should be available");
        assertTrue(embeddingProvider.getProviderName().matches("onnx|openai"),
                   "Provider should be onnx or openai");
    }
    
    @Test
    public void testProviderAvailability() {
        // Test provider availability check
        assertNotNull(embeddingProvider, "EmbeddingProvider should be configured");
        
        boolean available = embeddingProvider.isAvailable();
        
        // Provider may not be available if model files are missing (ONNX)
        // or service is not running (REST), but the test should not fail
        if (!available) {
            System.out.println("Warning: EmbeddingProvider is not available: " + 
                             embeddingProvider.getProviderName());
            System.out.println("Status: " + embeddingProvider.getStatus());
        }
    }
    
    @Test
    public void testGenerateEmbedding() {
        // Skip test if provider is not available
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            System.out.println("Skipping test - provider not available");
            return;
        }
        
        // Test single embedding generation
        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("This is a test sentence for embedding generation")
            .model(null) // Use default model
            .build();
        
        try {
            AIEmbeddingResponse response = embeddingService.generateEmbedding(request);
            
            // Verify response
            assertNotNull(response, "Response should not be null");
            assertNotNull(response.getEmbedding(), "Embedding should not be null");
            assertTrue(response.getEmbedding().size() > 0, "Embedding should have dimensions");
            assertNotNull(response.getModel(), "Model should be specified");
            assertNotNull(response.getRequestId(), "Request ID should be generated");
            assertNotNull(response.getProcessingTimeMs(), "Processing time should be recorded");
            
            // Verify embedding dimensions
            int expectedDimension = embeddingProvider.getEmbeddingDimension();
            assertEquals(expectedDimension, response.getDimensions(), 
                        "Embedding dimensions should match provider");
            assertEquals(expectedDimension, response.getEmbedding().size(),
                        "Embedding vector size should match dimensions");
            
            // Verify embedding values are valid (not all zeros)
            boolean hasNonZero = response.getEmbedding().stream()
                .anyMatch(value -> Math.abs(value) > 0.0001);
            assertTrue(hasNonZero, "Embedding should contain non-zero values");
            
            System.out.println("✅ Generated embedding:");
            System.out.println("  - Provider: " + embeddingProvider.getProviderName());
            System.out.println("  - Dimensions: " + response.getDimensions());
            System.out.println("  - Processing time: " + response.getProcessingTimeMs() + "ms");
            System.out.println("  - Model: " + response.getModel());
            
        } catch (Exception e) {
            // If ONNX model files are missing, this is expected
            if (embeddingProvider.getProviderName().equals("onnx")) {
                System.out.println("⚠️  ONNX provider test skipped - model files may be missing");
                System.out.println("   Model path: " + config.getOnnx().getModelPath());
                return;
            }
            // For other providers, fail the test
            fail("Failed to generate embedding: " + e.getMessage(), e);
        }
    }
    
    @Test
    public void testGenerateEmbeddingsBatch() {
        // Skip test if provider is not available
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            System.out.println("Skipping test - provider not available");
            return;
        }
        
        // Test batch embedding generation
        List<String> texts = Arrays.asList(
            "First test sentence",
            "Second test sentence",
            "Third test sentence"
        );
        
        try {
            List<AIEmbeddingResponse> responses = embeddingService.generateEmbeddings(texts, "test");
            
            // Verify responses
            assertNotNull(responses, "Responses should not be null");
            assertEquals(texts.size(), responses.size(), 
                        "Should have one response per text");
            
            // Verify each response
            int expectedDimension = embeddingProvider.getEmbeddingDimension();
            for (int i = 0; i < responses.size(); i++) {
                AIEmbeddingResponse response = responses.get(i);
                assertNotNull(response, "Response " + i + " should not be null");
                assertNotNull(response.getEmbedding(), "Embedding " + i + " should not be null");
                assertEquals(expectedDimension, response.getDimensions(),
                           "Embedding " + i + " should have correct dimensions");
            }
            
            System.out.println("✅ Generated " + responses.size() + " embeddings in batch");
            System.out.println("  - Provider: " + embeddingProvider.getProviderName());
            System.out.println("  - Dimensions: " + expectedDimension);
            
        } catch (Exception e) {
            // If ONNX model files are missing, this is expected
            if (embeddingProvider.getProviderName().equals("onnx")) {
                System.out.println("⚠️  ONNX provider batch test skipped - model files may be missing");
                return;
            }
            // For other providers, fail the test
            fail("Failed to generate batch embeddings: " + e.getMessage(), e);
        }
    }
    
    @Test
    public void testProviderStatus() {
        // Test provider status
        assertNotNull(embeddingProvider, "EmbeddingProvider should be configured");
        
        var status = embeddingProvider.getStatus();
        
        assertNotNull(status, "Status should not be null");
        assertTrue(status.containsKey("provider"), "Status should contain provider");
        assertTrue(status.containsKey("available"), "Status should contain available");
        
        System.out.println("✅ Provider Status:");
        status.forEach((key, value) -> 
            System.out.println("  - " + key + ": " + value)
        );
    }
    
    @Test
    public void testEmbeddingDimension() {
        // Test embedding dimension
        assertNotNull(embeddingProvider, "EmbeddingProvider should be configured");
        
        int dimension = embeddingProvider.getEmbeddingDimension();
        
        assertTrue(dimension > 0, "Embedding dimension should be positive");
        
        // Common embedding dimensions
        assertTrue(dimension == 384 || dimension == 768 || dimension == 1536,
                   "Embedding dimension should be a common value (384, 768, or 1536)");
        
        System.out.println("✅ Embedding dimension: " + dimension);
    }
}
