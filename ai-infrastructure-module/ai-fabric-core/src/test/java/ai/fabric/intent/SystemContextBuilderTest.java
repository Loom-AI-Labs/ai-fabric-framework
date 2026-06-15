package ai.fabric.intent;

import ai.fabric.intent.action.ActionInfo;
import ai.fabric.intent.action.AvailableActionsRegistry;
import ai.fabric.spi.BehaviorContextProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemContextBuilderTest {

    @Test
    void shouldAssembleContextFromRegistryAndKnowledgeBase() {
        AvailableActionsRegistry registry = Mockito.mock(AvailableActionsRegistry.class);
        KnowledgeBaseOverviewService overviewService = Mockito.mock(KnowledgeBaseOverviewService.class);

        Mockito.when(registry.getAllAvailableActions())
            .thenReturn(List.of(ActionInfo.builder().name("cancel_subscription").build()));

        KnowledgeBaseOverview overview = KnowledgeBaseOverview.builder()
            .totalIndexedDocuments(42)
            .build();
        Mockito.when(overviewService.getOverview()).thenReturn(overview);

        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T10:15:30Z"), ZoneId.of("UTC"));
        ObjectProvider<Clock> clockProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(clockProvider.getObject()).thenReturn(fixedClock);
        Mockito.when(clockProvider.getObject(Mockito.any())).thenReturn(fixedClock);
        Mockito.when(clockProvider.getIfAvailable()).thenReturn(fixedClock);
        Mockito.when(clockProvider.getIfUnique()).thenReturn(fixedClock);
        Mockito.when(clockProvider.getIfAvailable(Mockito.any())).thenReturn(fixedClock);

        // Create ObjectProvider mocks for all parameters
        ObjectProvider<BehaviorContextProvider> behaviorProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(behaviorProvider.getIfAvailable()).thenReturn(null);
        
        ObjectProvider<BeanFactory> beanFactoryProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(beanFactoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<KnowledgeBaseOverviewService> overviewProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(overviewProvider.getIfAvailable()).thenReturn(overviewService);
        
        SystemContextBuilder builder = new SystemContextBuilder(
            registry, 
            overviewProvider,
            behaviorProvider,
            clockProvider,
            beanFactoryProvider
        );

        SystemContext context = builder.buildContext("user-123");

        assertThat(context.getAvailableActions()).hasSize(1);
        assertThat(context.getKnowledgeBaseOverview().getTotalIndexedDocuments()).isEqualTo(42);
        assertThat(context.getUserId()).isEqualTo("user-123");
        assertThat(context.getTimestamp()).isEqualTo(LocalDateTime.of(2025, 1, 1, 10, 15, 30));
    }
}
