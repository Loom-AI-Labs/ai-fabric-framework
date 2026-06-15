package ai.fabric.intent.vectorspace;

import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.dto.Intent;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedFanOutRouterTest {

    @Test
    void shouldAutoAssignWhenOnlyOneEntityTypeExists() {
        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .entityTypes(List.of("policies"))
            .documentsByType(Map.of("policies", 10L))
            .build();

        BoundedFanOutRouter router = new BoundedFanOutRouter(providerReturning(overviewServiceReturning(overview)),
            new VectorSpaceRoutingProperties());

        RoutingResult result = router.route(Intent.builder().build(), "anything");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getVectorSpace()).isEqualTo("policies");
        assertThat(result.getStrategy()).isEqualTo(RoutingStrategy.AUTO_ASSIGN);
        assertThat(result.getCandidateSpaces()).isNull();
    }

    @Test
    void shouldMatchByQueryMentionWhenEntityTypeMentioned() {
        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .entityTypes(List.of("policies", "faq"))
            .documentsByType(Map.of("policies", 10L, "faq", 5L))
            .build();

        BoundedFanOutRouter router = new BoundedFanOutRouter(providerReturning(overviewServiceReturning(overview)),
            new VectorSpaceRoutingProperties());

        RoutingResult result = router.route(Intent.builder().build(), "Please search POLICIES for refund timeline");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getVectorSpace()).isEqualTo("policies");
        assertThat(result.getStrategy()).isEqualTo(RoutingStrategy.QUERY_MENTION);
    }

    @Test
    void shouldReturnFanOutCandidatesWhenMultipleEntityTypesAndNoMatch() {
        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .entityTypes(List.of("policies", "faq", "catalog"))
            .documentsByType(Map.of("policies", 10L, "faq", 50L, "catalog", 30L))
            .build();

        VectorSpaceRoutingProperties props = new VectorSpaceRoutingProperties();
        props.setStrategy(VectorSpaceRoutingProperties.Strategy.BOUNDED_FAN_OUT);
        props.setFanOutMaxSpaces(2);

        BoundedFanOutRouter router = new BoundedFanOutRouter(providerReturning(overviewServiceReturning(overview)), props);

        RoutingResult result = router.route(Intent.builder().build(), "tell me something unrelated");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStrategy()).isEqualTo(RoutingStrategy.FAN_OUT);
        assertThat(result.getCandidateSpaces()).containsExactly("faq", "catalog");
    }

    @Test
    void shouldPickLargestCountWhenHeuristicStrategySelected() {
        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .entityTypes(List.of("policies", "faq"))
            .documentsByType(Map.of("policies", 10L, "faq", 99L))
            .build();

        VectorSpaceRoutingProperties props = new VectorSpaceRoutingProperties();
        props.setStrategy(VectorSpaceRoutingProperties.Strategy.HEURISTIC);

        BoundedFanOutRouter router = new BoundedFanOutRouter(providerReturning(overviewServiceReturning(overview)), props);

        RoutingResult result = router.route(Intent.builder().build(), "no hints here");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getVectorSpace()).isEqualTo("faq");
        assertThat(result.getStrategy()).isEqualTo(RoutingStrategy.HEURISTIC);
    }

    @Test
    void shouldReturnClarificationWhenConfigured() {
        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .entityTypes(List.of("policies", "faq", "catalog"))
            .documentsByType(Map.of("policies", 10L, "faq", 50L, "catalog", 30L))
            .build();

        VectorSpaceRoutingProperties props = new VectorSpaceRoutingProperties();
        props.setStrategy(VectorSpaceRoutingProperties.Strategy.CLARIFICATION);
        props.setFanOutMaxSpaces(2);

        BoundedFanOutRouter router = new BoundedFanOutRouter(providerReturning(overviewServiceReturning(overview)), props);

        RoutingResult result = router.route(Intent.builder().build(), "no hints here");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStrategy()).isEqualTo(RoutingStrategy.CLARIFICATION);
        assertThat(result.getCandidateSpaces()).containsExactly("faq", "catalog");
    }

    @Test
    void shouldFailClosedWhenOverviewNotAvailable() {
        ObjectProvider<KnowledgeBaseOverviewService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        BoundedFanOutRouter router = new BoundedFanOutRouter(provider, new VectorSpaceRoutingProperties());

        RoutingResult result = router.route(Intent.builder().build(), "anything");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStrategy()).isEqualTo(RoutingStrategy.CLARIFICATION);
        assertThat(result.getRationale()).contains("No entity types available");
    }

    private ObjectProvider<KnowledgeBaseOverviewService> providerReturning(KnowledgeBaseOverviewService service) {
        ObjectProvider<KnowledgeBaseOverviewService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private KnowledgeBaseOverviewService overviewServiceReturning(KnowledgeBaseOverview overview) {
        KnowledgeBaseOverviewService service = mock(KnowledgeBaseOverviewService.class);
        when(service.getOverview()).thenReturn(overview);
        return service;
    }
}

