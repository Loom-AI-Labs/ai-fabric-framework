package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorSpaceSelectionSupportTest {

    @Test
    void shouldOrderVectorSpacesByKnowledgeBaseCountsThenEntityTypes() {
        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        Map<String, Long> byType = new LinkedHashMap<>();
        byType.put("product", 10L);
        byType.put("policy", 30L);
        byType.put("order", 20L);
        byType.put(" ", 99L);
        when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .documentsByType(byType)
            .entityTypes(List.of(" product ", "faq", "policy"))
            .build());

        VectorSpaceSelectionSupport support = new VectorSpaceSelectionSupport(
            providerOf(overviewService),
            null,
            null
        );

        assertThat(support.resolveAllVectorSpaces())
            .containsExactly("policy", "order", "product", "faq");
    }

    @Test
    void shouldFallbackToConfiguredVectorSpacesWhenOverviewUnavailable() {
        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        when(loader.getSupportedEntityTypes()).thenReturn(Set.of("Order", " product ", "Product", " "));

        VectorSpaceSelectionSupport support = new VectorSpaceSelectionSupport(
            providerOf((KnowledgeBaseOverviewService) null),
            loader,
            null
        );

        assertThat(support.resolveAllVectorSpaces()).containsExactly("order", "product");
        assertThat(support.resolveConfiguredVectorSpaces()).containsExactly("order", "product");
    }

    @Test
    void shouldCapDeterministicFallbackWithBudgetOrRoutingDefault() {
        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of("product", "policy", "order", "faq"))
            .build());
        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setFanOutMaxSpaces(2);
        VectorSpaceSelectionSupport support = new VectorSpaceSelectionSupport(
            providerOf(overviewService),
            null,
            routingProperties
        );

        assertThat(support.resolveDeterministicFallbackVectorSpaces(null))
            .containsExactly("product", "policy");
        assertThat(support.resolveDeterministicFallbackVectorSpaces(
            new OrchestrationPolicy.RagBudgets(null, 3, null, null, null, null, List.of(), null)
        )).containsExactly("product", "policy", "order");
    }

    @Test
    void shouldCapExplicitVectorSpacesOnlyWhenBudgetIsPositive() {
        VectorSpaceSelectionSupport support = new VectorSpaceSelectionSupport(null, null, null);
        List<String> spaces = List.of("product", "policy", "order");

        assertThat(support.capVectorSpacesToBudget(spaces, null)).isSameAs(spaces);
        assertThat(support.capVectorSpacesToBudget(
            spaces,
            new OrchestrationPolicy.RagBudgets(null, 2, null, null, null, null, List.of(), null)
        )).containsExactly("product", "policy");
        assertThat(support.capVectorSpacesToBudget(
            spaces,
            new OrchestrationPolicy.RagBudgets(null, 0, null, null, null, null, List.of(), null)
        )).isSameAs(spaces);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
