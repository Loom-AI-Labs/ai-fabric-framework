package ai.fabric.relationship.service;

import ai.fabric.core.AICoreService;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.llm.structured.DefaultStructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonCallSpec;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.llm.structured.StructuredJsonResult;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.relationship.cache.QueryCache;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.relationship.validation.RelationshipQueryValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelationshipQueryPlannerTest {

    @Mock
    private AICoreService aiCoreService;
    @Mock
    private RelationshipSchemaProvider schemaProvider;
    @Mock
    private RelationshipQueryValidator validator;
    @Mock
    private QueryCache queryCache;
    @Mock
    private QueryMetrics queryMetrics;

    private RelationshipQueryPlanner planner;

    @BeforeEach
    void setUp() {
        RelationshipQueryProperties properties = new RelationshipQueryProperties();
        when(schemaProvider.getSchemaDescription(any())).thenReturn("schema");
        when(queryCache.isEnabled()).thenReturn(true);
        when(queryMetrics.isEnabled()).thenReturn(true);
        planner = new RelationshipQueryPlanner(
            aiCoreService,
            schemaProvider,
            properties,
            validator,
            queryCache,
            queryMetrics,
            new ObjectMapper(),
            new StructuredJsonExtractor(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
    }

    @Test
    void shouldReturnPlanFromCacheWithoutCallingLLM() {
        RelationshipQueryPlan cached = RelationshipQueryPlan.builder()
            .originalQuery("cached")
            .primaryEntityType("document")
            .build();
        when(queryCache.getPlan(anyString())).thenReturn(Optional.of(cached));

        RelationshipQueryPlan result = planner.planQuery("Find cached docs", List.of("document"));

        assertThat(result).isSameAs(cached);
        verify(aiCoreService, never()).generateContent(any(AIGenerationRequest.class));
        verify(queryMetrics).recordPlan(any(Long.class), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void shouldPlanAndCacheResultWhenCacheMisses() {
        when(queryCache.getPlan(anyString())).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any()))
            .thenReturn(AIGenerationResponse.builder().content(samplePlanJson()).build());

        RelationshipQueryPlan result = planner.planQuery("Find docs", List.of("document"));

        assertThat(result.getPrimaryEntityType()).isEqualTo("document");
        assertThat(result.getConfidenceScore()).isEqualTo(0.8d);
        verify(validator).validate(any(RelationshipQueryPlan.class));
        verify(queryCache).putPlan(anyString(), any(RelationshipQueryPlan.class));
        verify(queryMetrics).recordPlan(any(Long.class), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void shouldParsePlanThroughStructuredJsonExecutor() {
        ObjectMapper objectMapper = new ObjectMapper();
        StructuredJsonExtractor extractor = new StructuredJsonExtractor();
        RecordingStructuredJsonCallExecutor recordingExecutor = new RecordingStructuredJsonCallExecutor(
            new DefaultStructuredJsonCallExecutor(extractor, objectMapper)
        );
        planner = new RelationshipQueryPlanner(
            aiCoreService,
            schemaProvider,
            new RelationshipQueryProperties(),
            validator,
            queryCache,
            queryMetrics,
            objectMapper,
            extractor,
            recordingExecutor,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        when(queryCache.getPlan(anyString())).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any()))
            .thenReturn(AIGenerationResponse.builder()
                .content("""
                    Here is the plan:
                    ```json
                    {
                      "originalQuery": "Find docs",
                      "primaryEntityType": "document",
                      "candidateEntityTypes": ["document"],
                      "relationshipPaths": [],
                      "directFilters": {},
                      "relationshipFilters": {},
                      "needsSemanticSearch": false,
                      "confidence": 0.8
                    }
                    ```
                    """)
                .build());

        RelationshipQueryPlan result = planner.planQuery("Find docs", List.of("document"));

        assertThat(recordingExecutor.calls).isEqualTo(1);
        assertThat(result.getPrimaryEntityType()).isEqualTo("document");
        assertThat(result.getConfidenceScore()).isEqualTo(0.8d);
    }

    @Test
    void shouldUseConfiguredMaxTokensForPlanningRequests() {
        RelationshipQueryProperties properties = new RelationshipQueryProperties();
        properties.getLlm().setMaxTokens(2400);

        planner = new RelationshipQueryPlanner(
            aiCoreService,
            schemaProvider,
            properties,
            validator,
            queryCache,
            queryMetrics,
            new ObjectMapper(),
            new StructuredJsonExtractor(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        when(queryCache.getPlan(anyString())).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any()))
            .thenReturn(AIGenerationResponse.builder().content(samplePlanJson()).build());

        planner.planQuery("Find docs", List.of("document"));

        ArgumentCaptor<AIGenerationRequest> captor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(captor.capture());
        assertThat(captor.getValue().getMaxTokens()).isEqualTo(2400);
    }

    @Test
    void shouldBuildDifferentCacheKeysWhenEntityTypesDiffer() {
        when(queryCache.getPlan(anyString())).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any()))
            .thenReturn(AIGenerationResponse.builder().content(samplePlanJson()).build());

        planner.planQuery("Find docs", List.of("document"));
        planner.planQuery("Find docs", List.of("product"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryCache, times(2)).getPlan(keyCaptor.capture());
        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    void shouldFallbackWhenLlmFails() {
        when(queryCache.getPlan(anyString())).thenReturn(Optional.empty());
        when(aiCoreService.generateContent(any())).thenThrow(new IllegalStateException("LLM down"));

        RelationshipQueryPlan fallback = planner.planQuery("Find docs", List.of("document"));

        assertThat(fallback.getPrimaryEntityType()).isEqualTo("document");
        assertThat(fallback.getCandidateEntityTypes()).contains("document");
        verify(queryMetrics).recordPlan(any(Long.class), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void shouldUseCandidateEntitiesWhenProvided() {
        when(queryCache.getPlan(anyString())).thenReturn(Optional.empty());
        when(schemaProvider.getSchemaDescription(any())).thenReturn("schema");
        when(aiCoreService.generateContent(any()))
            .thenReturn(AIGenerationResponse.builder().content(samplePlanJson()).build());

        RelationshipQueryPlan plan = planner.planQuery("Find docs", List.of("document", "user"));

        assertThat(plan.getCandidateEntityTypes()).contains("user");
    }

    private String samplePlanJson() {
        return """
            {
              "originalQuery": "Find docs",
              "primaryEntityType": "document",
            "candidateEntityTypes": ["document", "user"],
              "relationshipPaths": [],
              "directFilters": {},
              "relationshipFilters": {},
              "needsSemanticSearch": false,
              "confidence": 0.8
            }
            """;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    private static final class RecordingStructuredJsonCallExecutor implements StructuredJsonCallExecutor {
        private final StructuredJsonCallExecutor delegate;
        private int calls;

        private RecordingStructuredJsonCallExecutor(StructuredJsonCallExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> StructuredJsonResult<T> execute(StructuredJsonCallSpec<T> spec) {
            calls += 1;
            return delegate.execute(spec);
        }
    }
}
