package ai.fabric.chat.resolver;

import ai.fabric.chat.annotation.AIConfirmationInterceptors;
import ai.fabric.chat.annotation.OnPendingActionConfirmation;
import ai.fabric.chat.interception.ConfirmationInterceptionContext;
import ai.fabric.chat.interception.InterceptionDecision;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnotatedConfirmationInterceptorsResolverTest {

    @Test
    void shouldReplaceConfirmationIntentUsingAnnotatedInterceptor() {
        PendingActionStore store = new StackPendingActionStore();

        TestInterceptors bean = new TestInterceptors();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class))
            .thenReturn(Map.of("testInterceptors", bean));

        AnnotatedConfirmationInterceptorsResolver resolver = new AnnotatedConfirmationInterceptorsResolver(store, applicationContext);

        PipelineContext context = PipelineContext.from("yes", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId("conv-1")
            .build());

        store.pushPendingAction("conv-1", "demo-user", new PendingAction("cancel_purchase_order", Map.of("orderNumber", "PO-1"), null, Instant.now()));

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(java.util.List.of(Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build()))
            .build();

        assertThat(resolver.canResolve(response, Map.of(), context)).isTrue();

        PipelineContext updated = resolver.resolve(response, Map.of(), context);
        assertThat(updated.getIntentResponse()).isNotNull();
        assertThat(updated.getIntentResponse().getIntents()).hasSize(1);

        Intent updatedIntent = updated.getIntentResponse().getIntents().getFirst();
        assertThat(updatedIntent.getType()).isEqualTo(IntentType.ACTION);
        assertThat(updatedIntent.getAction()).isEqualTo("offer_order_discount");
        assertThat(updatedIntent.getActionParams()).containsEntry("orderNumber", "PO-1");

        PendingAction top = store.peekPendingAction("conv-1", "demo-user").orElseThrow();
        assertThat(top.action()).isEqualTo("cancel_purchase_order");
        assertThat(top.actionParams()).containsEntry("_offered", true);

        // Guard prevents repeated offering loops.
        assertThat(resolver.canResolve(response, Map.of(), context)).isFalse();
    }

    @Test
    void shouldMarkConfirmedActionsWhenInterceptorExecutesAction() {
        PendingActionStore store = new StackPendingActionStore();

        TestInterceptors bean = new TestInterceptors();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class))
            .thenReturn(Map.of("testInterceptors", bean));

        AnnotatedConfirmationInterceptorsResolver resolver = new AnnotatedConfirmationInterceptorsResolver(store, applicationContext);

        PipelineContext context = PipelineContext.from("yes", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId("conv-2")
            .build());

        store.pushPendingAction("conv-2", "demo-user", new PendingAction("get_active_orders", Map.of(), null, Instant.now()));

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(java.util.List.of(Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build()))
            .build();

        PipelineContext updated = resolver.resolve(response, Map.of(), context);

        assertThat(updated.getConfirmedActions()).contains("get_active_orders");
        assertThat(updated.getConfirmedActions()).hasSize(1);
        assertThat(updated.getIntentResponse()).isNotNull();
        assertThat(updated.getIntentResponse().getIntents()).hasSize(1);
        assertThat(updated.getIntentResponse().getIntents().getFirst().getAction()).isEqualTo("get_active_orders");
    }

    @Test
    void shouldPreserveNonConfirmationIntentsWhenAnnotatedInterceptorMatchesCompoundTurn() {
        PendingActionStore store = new StackPendingActionStore();

        TestInterceptors bean = new TestInterceptors();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class))
            .thenReturn(Map.of("testInterceptors", bean));

        AnnotatedConfirmationInterceptorsResolver resolver = new AnnotatedConfirmationInterceptorsResolver(store, applicationContext);

        PipelineContext context = PipelineContext.from("yes and show my orders", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId("conv-3")
            .build());

        store.pushPendingAction("conv-3", "demo-user", new PendingAction(
            "Cancel Subscription Action",
            Map.of("orderNumber", "PO-77"),
            null,
            Instant.now()
        ));

        MultiIntentResponse compound = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build(),
                Intent.builder().type(IntentType.ACTION).action("show_orders").confidence(0.9d).build()
            ))
            .build();

        assertThat(resolver.canResolve(compound, Map.of(), context)).isTrue();

        PipelineContext updated = resolver.resolve(compound, Map.of(), context);

        assertThat(updated.getIntentResponse().getIntents())
            .extracting(Intent::getAction)
            .containsExactly("offer_order_discount", "show_orders");

        PendingAction top = store.peekPendingAction("conv-3", "demo-user").orElseThrow();
        assertThat(top.actionParams()).containsEntry("_retentionofferoffered", true);
    }

    @Test
    void shouldSuppressCorrelatedActionIntentsWhenAnnotatedInterceptorPreservesCompoundFollowUps() {
        PendingActionStore store = new StackPendingActionStore();

        TestInterceptors bean = new TestInterceptors();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class))
            .thenReturn(Map.of("testInterceptors", bean));

        AnnotatedConfirmationInterceptorsResolver resolver = new AnnotatedConfirmationInterceptorsResolver(store, applicationContext);

        PipelineContext context = PipelineContext.from("yes and show my orders", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId("conv-4")
            .build());

        store.pushPendingAction("conv-4", "demo-user", new PendingAction(
            "Cancel Subscription Action",
            Map.of("orderNumber", "PO-88"),
            null,
            Instant.now()
        ));

        MultiIntentResponse compound = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build(),
                Intent.builder().type(IntentType.ACTION).action("cancel_subscription").confidence(0.9d).build(),
                Intent.builder().type(IntentType.ACTION).action("offer_order_discount").confidence(0.9d).build(),
                Intent.builder().type(IntentType.ACTION).action("show_orders").confidence(0.9d).build()
            ))
            .build();

        PipelineContext updated = resolver.resolve(compound, Map.of(), context);

        assertThat(updated.getIntentResponse().getIntents())
            .extracting(Intent::getAction)
            .containsExactly("offer_order_discount", "show_orders");
    }

    @Test
    void shouldHonorOnceParamRegardlessOfStoredKeyCase() {
        PendingActionStore store = new StackPendingActionStore();

        TestInterceptors bean = new TestInterceptors();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class))
            .thenReturn(Map.of("testInterceptors", bean));

        AnnotatedConfirmationInterceptorsResolver resolver = new AnnotatedConfirmationInterceptorsResolver(store, applicationContext);

        PipelineContext context = PipelineContext.from("yes", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId("conv-5")
            .build());

        store.pushPendingAction("conv-5", "demo-user", new PendingAction(
            "cancel_subscription",
            Map.of("orderNumber", "PO-99", "_retentionofferoffered", true),
            null,
            Instant.now()
        ));

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build()))
            .build();

        assertThat(resolver.canResolve(response, Map.of(), context)).isFalse();
    }

    @Test
    void shouldNotResolveExpiredPendingActionWithAnnotatedInterceptor() {
        PendingActionStore store = new StackPendingActionStore();

        TestInterceptors bean = new TestInterceptors();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class))
            .thenReturn(Map.of("testInterceptors", bean));

        AnnotatedConfirmationInterceptorsResolver resolver = new AnnotatedConfirmationInterceptorsResolver(store, applicationContext);

        PipelineContext context = PipelineContext.from("yes", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId("conv-expired")
            .build());

        PendingAction expired = new PendingAction(
            "get_active_orders",
            Map.of(),
            null,
            Instant.now().minusSeconds(600)
        );
        store.pushPendingAction("conv-expired", "demo-user", expired);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build()))
            .build();

        assertThat(resolver.canResolve(response, Map.of(), context)).isFalse();

        PipelineContext updated = resolver.resolve(response, Map.of(), context);
        assertThat(updated).isSameAs(context);
        assertThat(store.peekPendingAction("conv-expired", "demo-user")).contains(expired);
    }

    @AIConfirmationInterceptors
    static class TestInterceptors {

        @OnPendingActionConfirmation(
            pendingActions = {"cancel_purchase_order"},
            confirmation = IntentType.CONFIRMATION_POSITIVE,
            onceParam = "_offered"
        )
        public InterceptionDecision offerDiscount(ConfirmationInterceptionContext ctx) {
            return ctx.promptAction("offer_order_discount", Map.of(
                "orderNumber", ctx.pending().actionParams().get("orderNumber"),
                "discountPercent", 10
            ));
        }

        @OnPendingActionConfirmation(
            pendingActions = {"get_active_orders"},
            confirmation = IntentType.CONFIRMATION_POSITIVE
        )
        public InterceptionDecision executeReadOnly(ConfirmationInterceptionContext ctx) {
            return ctx.executeAction("get_active_orders", Map.of());
        }

        @OnPendingActionConfirmation(
            pendingActions = {"cancel_subscription"},
            confirmation = IntentType.CONFIRMATION_POSITIVE,
            onceParam = "_RetentionOfferOffered",
            requireSingleIntent = false
        )
        public InterceptionDecision compoundOfferDiscount(ConfirmationInterceptionContext ctx) {
            return ctx.promptAction("offer_order_discount", Map.of(
                "orderNumber", ctx.pending().actionParams().get("orderNumber"),
                "discountPercent", 10
            ));
        }
    }

    static final class StackPendingActionStore implements PendingActionStore {

        private final Map<String, Deque<PendingAction>> stacks = new ConcurrentHashMap<>();

        @Override
        public Optional<PendingAction> getPendingAction(String conversationId, String ownerId) {
            return peekPendingAction(conversationId, ownerId);
        }

        @Override
        public void savePendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
            clearPendingActions(conversationId, ownerId);
            pushPendingAction(conversationId, ownerId, pendingAction);
        }

        @Override
        public void clearPendingAction(String conversationId, String ownerId) {
            clearPendingActions(conversationId, ownerId);
        }

        @Override
        public Optional<PendingAction> peekPendingAction(String conversationId, String ownerId) {
            Deque<PendingAction> stack = stacks.get(key(conversationId, ownerId));
            return stack == null ? Optional.empty() : Optional.ofNullable(stack.peek());
        }

        @Override
        public void pushPendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
            if (pendingAction == null) {
                return;
            }
            stacks.computeIfAbsent(key(conversationId, ownerId), ignored -> new ArrayDeque<>()).push(pendingAction);
        }

        @Override
        public Optional<PendingAction> popPendingAction(String conversationId, String ownerId) {
            Deque<PendingAction> stack = stacks.get(key(conversationId, ownerId));
            if (stack == null) {
                return Optional.empty();
            }
            PendingAction popped = stack.poll();
            return Optional.ofNullable(popped);
        }

        @Override
        public void clearPendingActions(String conversationId, String ownerId) {
            stacks.remove(key(conversationId, ownerId));
        }

        private String key(String conversationId, String ownerId) {
            return conversationId + "::" + ownerId;
        }
    }
}
