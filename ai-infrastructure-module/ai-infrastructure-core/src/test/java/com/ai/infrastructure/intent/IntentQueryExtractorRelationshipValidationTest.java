package com.ai.infrastructure.intent;

import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.extraction.IntentExtractionInput;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for IntentQueryExtractor's relationship query parameter validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IntentQueryExtractor - Relationship Query Validation")
class IntentQueryExtractorRelationshipValidationTest {

    @Mock
    private AICoreService aiCoreService;

    @Mock
    private EnrichedPromptBuilder enrichedPromptBuilder;

    @Mock
    private AIActionRegistry actionHandlerRegistry;

    private IntentQueryExtractor extractor;

    private static IntentExtractionInput input(String userQuery) {
        return new IntentExtractionInput(userQuery, userQuery, List.of());
    }

    @BeforeEach
    void setUp() {
        when(enrichedPromptBuilder.buildSystemPrompt(any(OrchestrationContext.class)))
            .thenReturn("system-prompt");

        extractor = new IntentQueryExtractor(
            aiCoreService,
            enrichedPromptBuilder,
            actionHandlerRegistry,
            new ObjectMapper(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    @Test
    @DisplayName("Should normalize entityTypes to lowercase")
    void shouldNormalizeEntityTypesToLowercase() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "query": "find customers",
                    "entityTypes": ["CUSTOMER", "Order", "ProDuCt"]
                  }
                }
              ]
            }
            """;
        
        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());
        
        // Act
        MultiIntentResponse response = extractor.extract(
            input("find customers"),
            OrchestrationContext.forUser("user-123")
        );
        
        // Assert
        Intent intent = response.getIntents().get(0);
        @SuppressWarnings("unchecked")
        List<String> entityTypes = (List<String>) intent.getActionParams().get("entityTypes");
        
        assertThat(entityTypes)
            .as("Entity types should be normalized to lowercase")
            .containsExactly("customer", "order", "product");
    }

    @Test
    @DisplayName("Should set empty list when entityTypes missing")
    void shouldSetEmptyListWhenEntityTypesMissing() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "query": "find stuff"
                  }
                }
              ]
            }
            """;
        
        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());
        
        // Act
        MultiIntentResponse response = extractor.extract(
            input("find stuff"),
            OrchestrationContext.forUser("user-123")
        );
        
        // Assert
        Intent intent = response.getIntents().get(0);
        assertThat(intent.getActionParams())
            .as("ActionParams should contain entityTypes key")
            .containsKey("entityTypes");
        
        @SuppressWarnings("unchecked")
        List<String> entityTypes = (List<String>) intent.getActionParams().get("entityTypes");
        assertThat(entityTypes)
            .as("EntityTypes should be empty list (not null)")
            .isNotNull()
            .isEmpty();
    }

    @Test
    @DisplayName("Should convert string entityTypes to list")
    void shouldConvertStringEntityTypesToList() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "query": "find customers",
                    "entityTypes": "customer"
                  }
                }
              ]
            }
            """;
        
        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());
        
        // Act
        MultiIntentResponse response = extractor.extract(
            input("find customers"),
            OrchestrationContext.forUser("user-123")
        );
        
        // Assert
        Intent intent = response.getIntents().get(0);
        @SuppressWarnings("unchecked")
        List<String> entityTypes = (List<String>) intent.getActionParams().get("entityTypes");
        
        assertThat(entityTypes)
            .as("String entityType should be converted to List")
            .containsExactly("customer");
    }

    @Test
    @DisplayName("Should filter out null and empty entity types")
    void shouldFilterOutNullAndEmptyEntityTypes() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "query": "find stuff",
                    "entityTypes": ["customer", null, "", "  ", "order"]
                  }
                }
              ]
            }
            """;
        
        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());
        
        // Act
        MultiIntentResponse response = extractor.extract(
            input("find stuff"),
            OrchestrationContext.forUser("user-123")
        );
        
        // Assert
        Intent intent = response.getIntents().get(0);
        @SuppressWarnings("unchecked")
        List<String> entityTypes = (List<String>) intent.getActionParams().get("entityTypes");
        
        assertThat(entityTypes)
            .as("Null and blank entity types should be filtered out")
            .containsExactly("customer", "order");
    }

    @Test
    @DisplayName("Should default relationship_query actionParams.query when missing (strip hint prefixes)")
    void shouldDefaultRelationshipQueryWhenMissing() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "entityTypes": ["brand"],
                    "limit": 20
                  }
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        // Act
        MultiIntentResponse response = extractor.extract(
            input("relationship query: find all brands"),
            OrchestrationContext.forUser("user-123")
        );

        // Assert
        Intent intent = response.getIntents().get(0);
        assertThat(intent.getActionParams()).containsEntry("query", "find all brands");
    }

    @Test
    @DisplayName("Should strip relationship_query hint prefixes from actionParams.query when provided by LLM")
    void shouldPreserveQueryWhenProvidedByLLM() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "query": "relationship query: find all brands",
                    "entityTypes": ["brand"],
                    "limit": 20
                  }
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        // Act
        MultiIntentResponse response = extractor.extract(
            input("relationship query: find all brands"),
            OrchestrationContext.forUser("user-123")
        );

        // Assert
        Intent intent = response.getIntents().get(0);
        assertThat(intent.getActionParams()).containsEntry("query", "find all brands");
    }

    @Test
    @DisplayName("Should treat action='relationship query' as relationship_query for param normalization")
    void shouldNormalizeRelationshipQueryParamsWhenActionHasSpaces() {
        // Arrange
        when(actionHandlerRegistry.findMetadata("relationship query"))
            .thenReturn(Optional.of(AIActionMetaData.builder().name("relationship_query").build()));

        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship query",
                  "actionParams": {
                    "entityTypes": ["transaction"]
                  }
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        // Act
        MultiIntentResponse response = extractor.extract(
            input("relationship query: find transactions over $10000"),
            OrchestrationContext.forUser("user-123")
        );

        // Assert
        Intent intent = response.getIntents().get(0);
        assertThat(intent.getActionParams())
            .containsEntry("query", "find transactions over $10000");
    }

    @Test
    @DisplayName("Should preserve original relationship_query query text when actionParams.query missing")
    void shouldPreserveOriginalQueryWhenMissingQueryParam() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "relationship_query",
                  "action": "relationship_query",
                  "actionParams": {
                    "entityTypes": ["product"]
                  }
                }
              ]
            }
            """;

        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());

        // Act
        MultiIntentResponse response = extractor.extract(
            input("relationship query: find products under $100 and then explain why they are good options"),
            OrchestrationContext.forUser("user-123")
        );

        // Assert
        Intent intent = response.getIntents().get(0);
        assertThat(intent.getActionParams())
            .containsEntry("query", "find products under $100 and then explain why they are good options");
    }

    @Test
    @DisplayName("Should not validate non-relationship-query actions")
    void shouldNotValidateNonRelationshipQueryActions() {
        // Arrange
        String json = """
            {
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "cancel_subscription",
                  "action": "cancel_subscription",
                  "actionParams": {
                    "reason": "too expensive"
                  }
                }
              ]
            }
            """;
        
        when(aiCoreService.generateContent(any(), any(LlmPurpose.class)))
            .thenReturn(AIGenerationResponse.builder().content(json).build());
        
        // Act
        MultiIntentResponse response = extractor.extract(
            input("cancel subscription"),
            OrchestrationContext.forUser("user-123")
        );
        
        // Assert
        Intent intent = response.getIntents().get(0);
        assertThat(intent.getActionParams())
            .as("Non-relationship-query actions should not have entityTypes added")
            .doesNotContainKey("entityTypes");
    }
}
