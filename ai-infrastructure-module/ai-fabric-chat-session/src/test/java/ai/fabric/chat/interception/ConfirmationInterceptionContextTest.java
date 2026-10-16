package ai.fabric.chat.interception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.chat.control.DefaultConversationControlGateway;
import ai.fabric.chat.control.JdbcConversationControlStore;
import ai.fabric.chat.storage.ConversationAwarePendingActionStore;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.conversation.ConversationStateBinding;
import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ConfirmationInterceptionContextTest {

    private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");

    @Test
    void interceptorDoesNotAdoptAnActionInsertedAfterItsObservedTopWasConsumed() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:confirmation-context-" + java.util.UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1");
        ConversationControlGateway gateway = new DefaultConversationControlGateway(
            new JdbcConversationControlStore(dataSource, new ObjectMapper(), true),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ConversationScope controlScope = new ConversationScope(
            "tenant-1", "deployment-1", "conversation-1", "owner-1"
        );
        gateway.status(controlScope);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("conversationControlGateway", gateway);
        ConversationAwarePendingActionStore store = new ConversationAwarePendingActionStore(
            new InMemoryPendingActionStore(),
            beans.getBeanProvider(ConversationControlGateway.class),
            8
        );
        ConversationStateScope stateScope = new ConversationStateScope(
            "tenant-1", "deployment-1", "conversation-1", "owner-1", 0L
        );
        PendingAction cancellationA = pending("cancel_order_a");
        PendingAction offerA = pending("offer_order_a");
        PendingAction cancellationB = pending("cancel_order_b");
        store.pushPendingAction(stateScope, cancellationA);
        store.pushPendingAction(stateScope, offerA);

        Intent negative = Intent.builder().type(IntentType.CONFIRMATION_NEGATIVE).build();
        ConfirmationInterceptionContext context = new ConfirmationInterceptionContext(
            store,
            canonicalContext(),
            MultiIntentResponse.builder().intents(List.of(negative)).build(),
            offerA,
            negative
        );

        assertThat(context.popPending()).isEqualTo(offerA);
        store.pushPendingAction(stateScope, cancellationB);

        assertThat(context.peekPending()).isEqualTo(cancellationA);
        assertThatThrownBy(context::popPending)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stack changed");
        assertThat(store.getPendingActionStack(stateScope))
            .containsExactly(cancellationB, cancellationA);
    }

    private PipelineContext canonicalContext() {
        OrchestrationContext orchestration = OrchestrationContext.builder()
            .userId("owner-1")
            .conversationId("conversation-1")
            .approvedConversationSnapshot(new ApprovedConversationSnapshot(
                "turn-1",
                "owner-1",
                "conversation-1",
                "specialist-1",
                "a".repeat(64),
                0L,
                List.of(),
                NOW
            ))
            .build();
        TrustedExecutionContext trusted = new TrustedExecutionContext(
            new ExecutionPrincipal("service-1", ExecutionPrincipalType.SERVICE),
            null,
            ExecutionSource.APPLICATION,
            "tenant-1",
            "deployment-1",
            Set.of(),
            "correlation-1",
            NOW
        );
        return PipelineContext.from(new OrchestrationRequest(
            "No, continue with the cancellation",
            orchestration,
            trusted,
            ConversationPersistencePolicy.READ_ONLY,
            null,
            null,
            null,
            OrchestrationRequestPurpose.GENERAL,
            OrchestrationIntentPolicy.MODEL_DIRECTED,
            new ConversationStateBinding(
                "tenant-1", "deployment-1", "conversation-1", "owner-1", 0L
            )
        ));
    }

    private PendingAction pending(String action) {
        return new PendingAction(action, Map.of("id", action + "-id"), null, NOW);
    }
}
