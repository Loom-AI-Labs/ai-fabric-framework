package com.ai.infrastructure.intent;

import com.ai.infrastructure.intent.action.ActionInfo;
import com.ai.infrastructure.intent.action.AIActionParamSchema;
import com.ai.infrastructure.intent.action.AIActionParamType;
import com.ai.infrastructure.intent.action.AvailableActionsRegistry;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichedPromptBuilderTest {

    @Test
    void shouldIncludeActionsKnowledgeBaseAndGuidanceInPrompt() {
        AvailableActionsRegistry registry = Mockito.mock(AvailableActionsRegistry.class);
        KnowledgeBaseOverviewService overviewService = Mockito.mock(KnowledgeBaseOverviewService.class);

        Mockito.when(registry.getAllAvailableActions()).thenReturn(List.of(
            ActionInfo.builder()
                .name("cancel_subscription")
                .description("Cancel active subscription")
                .category("subscription")
                .build(),
            ActionInfo.builder()
                .name("commerce_update_cart")
                .description("Update cart")
                .category("commerce")
                .parameterSchemas(Map.of(
                    "cart_update_confirmation",
                    AIActionParamSchema.builder()
                        .name("cart_update_confirmation")
                        .type(AIActionParamType.STRING)
                        .required(false)
                        .description("Presentation-only shopper-facing confirmation phrase")
                        .build()
                ))
                .build()
        ));

        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .totalIndexedDocuments(123)
            .documentsByType(java.util.Map.of("policies", 80L, "faq", 43L))
            .build();
        Mockito.when(overviewService.getOverview()).thenReturn(overview);

        ObjectProvider<Clock> clockProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(clockProvider.getObject()).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getObject(Mockito.any())).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getIfAvailable()).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getIfUnique()).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getIfAvailable(Mockito.any())).thenAnswer(invocation -> {
            java.util.function.Supplier<Clock> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        ObjectProvider<com.ai.infrastructure.spi.BehaviorContextProvider> behaviorProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(behaviorProvider.getIfAvailable()).thenReturn(null);
        
        ObjectProvider<org.springframework.beans.factory.BeanFactory> beanFactoryProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(beanFactoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<KnowledgeBaseOverviewService> overviewProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(overviewProvider.getIfAvailable()).thenReturn(overviewService);
        
        SystemContextBuilder contextBuilder = new SystemContextBuilder(
            registry, 
            overviewProvider,
            behaviorProvider,
            clockProvider,
            beanFactoryProvider
        );

        PromptTemplateResolver promptTemplateResolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
        EnrichedPromptBuilder promptBuilder = new EnrichedPromptBuilder(
            contextBuilder,
            promptTemplateResolver,
            new PromptRenderer()
        );

        String prompt = promptBuilder.buildSystemPrompt("user-123");

        assertThat(prompt)
            .contains("AVAILABLE ACTIONS")
            .contains("cancel_subscription")
            .contains("cart_update_confirmation:string - Presentation-only shopper-facing confirmation phrase")
            .contains("Optional actionParams are allowed when the paramsSchema/description says they improve presentation")
            .contains("For catalog/search actions with a valid required `query` parameter")
            .contains("KNOWLEDGE BASE OVERVIEW")
            .contains("Total documents: 123")
            .contains("Available vectorSpace values:")
            .contains("NEXT-STEP RECOMMENDATIONS")
            .contains("requiresGeneration")
            .contains("needsAdvancedRAG")
            .contains("optimizedQuery")
            .contains("OUTPUT JSON SCHEMA");
    }

    @Test
    void shouldUseManagedPromptSectionsAsEffectiveSystemInstructions() {
        AvailableActionsRegistry registry = Mockito.mock(AvailableActionsRegistry.class);
        KnowledgeBaseOverviewService overviewService = Mockito.mock(KnowledgeBaseOverviewService.class);

        Mockito.when(registry.getAllAvailableActions()).thenReturn(List.of());
        Mockito.when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder().totalIndexedDocuments(0).build());

        ObjectProvider<Clock> clockProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(clockProvider.getObject()).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getObject(Mockito.any())).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getIfAvailable()).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getIfUnique()).thenReturn(Clock.systemUTC());
        Mockito.when(clockProvider.getIfAvailable(Mockito.any())).thenAnswer(invocation -> {
            java.util.function.Supplier<Clock> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        ObjectProvider<com.ai.infrastructure.spi.BehaviorContextProvider> behaviorProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(behaviorProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<org.springframework.beans.factory.BeanFactory> beanFactoryProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(beanFactoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<KnowledgeBaseOverviewService> overviewProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(overviewProvider.getIfAvailable()).thenReturn(overviewService);

        SystemContextBuilder contextBuilder = new SystemContextBuilder(
            registry,
            overviewProvider,
            behaviorProvider,
            clockProvider,
            beanFactoryProvider
        );

        PromptTemplateResolver promptTemplateResolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
        EnrichedPromptBuilder promptBuilder = new EnrichedPromptBuilder(
            contextBuilder,
            promptTemplateResolver,
            new PromptRenderer()
        );

        String prompt = promptBuilder.buildSystemPrompt(
            OrchestrationContext.builder()
                .userId("user-123")
                .metadata(Map.of(
                    "promptPreview",
                    Map.of(
                        "systemPrompt", "Custom system role.",
                        "intentExtractionPrompt", "Classify with custom guidance.",
                        "actionSelectionPrompt", "Use actions only when they are safe.",
                        "clarificationPrompt", "Ask one precise follow-up."
                    )
                ))
                .build()
        );

        assertThat(prompt)
            .contains("Custom system role.")
            .contains("Classify with custom guidance.")
            .contains("Use actions only when they are safe.")
            .contains("Ask one precise follow-up.")
            .contains("explicit item name or identifier")
            .doesNotContain("PREVIEW SYSTEM OVERRIDE")
            .doesNotContain("You are the intent extraction engine powering our Retrieval-Augmented Generation (RAG) assistant.");
    }
}
