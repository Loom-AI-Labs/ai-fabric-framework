package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionListPayload;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import com.ai.infrastructure.intent.vectorspace.RankBasedMerger;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.spi.AdvancedRAGProvider;
import com.ai.infrastructure.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentHandlingStepReadProbeFallbackVisibilityTest {

    @Test
    void shouldNotIncludeReadProbeMetadataByDefault() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("list_orders")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(any(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("No orders.")
            .data(ActionListPayload.of(List.of()))
            .build());

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("list_orders")
            .description("List my orders")
            .accessMode(ActionAccessMode.READ)
            .build();
        when(registry.findMetadata("list_orders")).thenReturn(Optional.of(meta));

        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any())).thenReturn(RAGResponse.builder()
            .documents(List.of())
            .context("No relevant context found.")
            .success(true)
            .build());

        AIServiceConfig aiServiceConfig = new AIServiceConfig();
        aiServiceConfig.getFeatures().setEnableGeneration(false);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(ragProvider),
            mock(AICoreService.class),
            aiServiceConfig,
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("list_orders")
            .actionParams(Map.of())
            .vectorSpace("order")
            .optimizedQuery("List my orders")
            .build();

        PipelineContext context = PipelineContext.from("List my orders", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMetadata()).doesNotContainKey("readProbe");
    }

    @Test
    void shouldExposeReadProbeMetadataWhenEnabled() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("list_orders")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(any(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("No orders.")
            .data(ActionListPayload.of(List.of()))
            .build());

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("list_orders")
            .description("List my orders")
            .accessMode(ActionAccessMode.READ)
            .build();
        when(registry.findMetadata("list_orders")).thenReturn(Optional.of(meta));

        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any())).thenReturn(RAGResponse.builder()
            .documents(List.of())
            .context("No relevant context found.")
            .success(true)
            .build());

        AIServiceConfig aiServiceConfig = new AIServiceConfig();
        aiServiceConfig.getFeatures().setEnableGeneration(false);

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "support_resolver",
            null,
            null,
            new OrchestrationPolicy.OrchestrationCapabilities(true, true, false, true, true, false, true, false, false, true, false, false),
            null
        );

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(ragProvider),
            mock(AICoreService.class),
            aiServiceConfig,
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("list_orders")
            .actionParams(Map.of())
            .vectorSpace("order")
            .optimizedQuery("List my orders")
            .build();

        PipelineContext context = PipelineContext.from("List my orders", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .orchestrationPolicy(policy)
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMetadata()).isNotNull();
        assertThat(result.getMetadata()).containsKey("readProbe");
        assertThat(result.getMetadata().get("readProbe")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> probe = (Map<String, Object>) result.getMetadata().get("readProbe");
        assertThat(probe.get("action")).isEqualTo("list_orders");
        assertThat(probe.get("accessMode")).isEqualTo("READ");
        assertThat(probe.get("success")).isEqualTo(true);
        assertThat(probe.get("emptyPayload")).isEqualTo(true);
        assertThat(probe.get("fallbackToRag")).isEqualTo(true);
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
