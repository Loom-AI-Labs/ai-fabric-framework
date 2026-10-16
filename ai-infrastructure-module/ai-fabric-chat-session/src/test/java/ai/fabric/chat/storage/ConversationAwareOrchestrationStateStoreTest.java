package ai.fabric.chat.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.fabric.chat.control.DefaultConversationControlGateway;
import ai.fabric.chat.control.JdbcConversationControlStore;
import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ConversationAwareOrchestrationStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");

    private ConversationControlGateway gateway;
    private ConversationStateScope scope;
    private PendingActionStore legacyPending;
    private ActionDraftStore legacyDraft;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:orchestration-state-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        gateway = new DefaultConversationControlGateway(
            new JdbcConversationControlStore(dataSource, new ObjectMapper(), true),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        scope = new ConversationStateScope(
            "tenant-1", "deployment-1", "conversation-1", "owner-1", 0L
        );
        gateway.status(controlScope(scope));
        legacyPending = mock(PendingActionStore.class);
        legacyDraft = mock(ActionDraftStore.class);
    }

    @Test
    void canonicalPendingActionsAndDraftsShareTheDurableScopedState() {
        ConversationAwarePendingActionStore pendingStore = new ConversationAwarePendingActionStore(
            legacyPending,
            provider(gateway),
            3
        );
        ConversationAwareActionDraftStore draftStore = new ConversationAwareActionDraftStore(
            legacyDraft,
            provider(gateway)
        );
        PendingAction first = pending("first_action");
        PendingAction second = pending("second_action");
        ActionDraft draft = new ActionDraft(
            "request_test_drive",
            Map.of("vehicleId", "vehicle-1"),
            "email is required",
            NOW,
            NOW
        );

        pendingStore.pushPendingAction(scope, first);
        pendingStore.pushPendingAction(scope, second);
        draftStore.saveDraft(scope, draft);

        assertThat(pendingStore.getPendingActionStack(scope))
            .extracting(PendingAction::action)
            .containsExactly("second_action", "first_action");
        assertThat(pendingStore.popPendingAction(scope)).contains(second);
        assertThat(draftStore.peekDraft(scope)).contains(draft);
        assertThat(draftStore.popDraft(scope)).contains(draft);
        assertThat(draftStore.peekDraft(scope)).isEmpty();
        assertThat(pendingStore.peekPendingAction(scope)).contains(first);
        verifyNoInteractions(legacyPending, legacyDraft);
    }

    @Test
    void canonicalCallsFailClosedWhenConversationControlIsUnavailable() {
        ConversationAwarePendingActionStore pendingStore = new ConversationAwarePendingActionStore(
            legacyPending,
            provider(null),
            3
        );
        ConversationAwareActionDraftStore draftStore = new ConversationAwareActionDraftStore(
            legacyDraft,
            provider(null)
        );

        assertThatThrownBy(() -> pendingStore.peekPendingAction(scope))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ConversationControlGateway");
        assertThatThrownBy(() -> draftStore.peekDraft(scope))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ConversationControlGateway");
        verifyNoInteractions(legacyPending, legacyDraft);
    }

    @Test
    void canonicalPendingConsumptionUsesCompareAndConsumeSemantics() {
        ConversationAwarePendingActionStore pendingStore = new ConversationAwarePendingActionStore(
            legacyPending,
            provider(gateway),
            3
        );
        PendingAction first = pending("first_action");
        PendingAction second = pending("second_action");
        pendingStore.pushPendingAction(scope, first);
        pendingStore.pushPendingAction(scope, second);

        assertThat(pendingStore.popPendingAction(scope, first)).isEmpty();
        assertThat(pendingStore.getPendingActionStack(scope)).containsExactly(second, first);
        assertThat(pendingStore.replacePendingActionStack(
            scope,
            List.of(first),
            List.of()
        )).isFalse();
        assertThat(pendingStore.popPendingAction(scope, second)).contains(second);
        assertThat(pendingStore.getPendingActionStack(scope)).containsExactly(first);
        verifyNoInteractions(legacyPending);
    }

    @Test
    void legacyPendingStackOperationsDelegateWithoutFlatteningTheStack() {
        ConversationAwarePendingActionStore pendingStore = new ConversationAwarePendingActionStore(
            legacyPending,
            provider(gateway),
            3
        );
        PendingAction first = pending("first_action");
        PendingAction second = pending("second_action");
        List<PendingAction> stack = List.of(second, first);
        when(legacyPending.peekPendingAction("legacy-conversation", "owner-1"))
            .thenReturn(Optional.of(second));
        when(legacyPending.popPendingAction("legacy-conversation", "owner-1"))
            .thenReturn(Optional.of(second));
        when(legacyPending.getPendingActionStack("legacy-conversation", "owner-1"))
            .thenReturn(stack);

        assertThat(pendingStore.peekPendingAction("legacy-conversation", "owner-1")).contains(second);
        pendingStore.pushPendingAction("legacy-conversation", "owner-1", first);
        assertThat(pendingStore.popPendingAction("legacy-conversation", "owner-1")).contains(second);
        assertThat(pendingStore.getPendingActionStack("legacy-conversation", "owner-1"))
            .containsExactly(second, first);
        pendingStore.replacePendingActionStack("legacy-conversation", "owner-1", stack);
        pendingStore.clearPendingActions("legacy-conversation", "owner-1");

        verify(legacyPending).pushPendingAction("legacy-conversation", "owner-1", first);
        verify(legacyPending).replacePendingActionStack("legacy-conversation", "owner-1", stack);
        verify(legacyPending).clearPendingActions("legacy-conversation", "owner-1");
    }

    private PendingAction pending(String action) {
        return new PendingAction(action, Map.of("id", action + "-id"), null, NOW);
    }

    private ConversationScope controlScope(ConversationStateScope value) {
        return new ConversationScope(
            value.tenantId(), value.deploymentId(), value.conversationId(), value.ownerId()
        );
    }

    private ObjectProvider<ConversationControlGateway> provider(ConversationControlGateway value) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (value != null) {
            beans.addBean("conversationControlGateway", value);
        }
        return beans.getBeanProvider(ConversationControlGateway.class);
    }
}
