package com.subscription.hub.controller;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.PendingAction;
import com.subscription.hub.ai.ResolverChatHistoryEnrichmentStep;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NaturalLanguageControllerTest {

    @Test
    void queryPassesBoundedChatHistoryToOrchestrationContext() {
        RAGOrchestrator ragOrchestrator = mock(RAGOrchestrator.class);
        when(ragOrchestrator.orchestrate(eq("ok add it"), any(OrchestrationContext.class)))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Processed")
                .build());

        NaturalLanguageController.ChatHistoryMessage userTurn = new NaturalLanguageController.ChatHistoryMessage();
        userTurn.setRole("user");
        userTurn.setContent("Why can't I place an order?");
        NaturalLanguageController.ChatHistoryMessage assistantTurn = new NaturalLanguageController.ChatHistoryMessage();
        assistantTurn.setRole("assistant");
        assistantTurn.setContent("Your account is blocked by a missing payment method.");

        NaturalLanguageController.QueryRequest request = new NaturalLanguageController.QueryRequest();
        request.setQuery("ok add it");
        request.setUserId("92");
        request.setSessionId("session-1");
        request.setConversationId("resolver-session-1");
        request.setMode("resolver");
        request.setPosition("resolver");
        request.setHistoryMessages(List.of(userTurn, assistantTurn));

        NaturalLanguageController controller = new NaturalLanguageController();
        ReflectionTestUtils.setField(controller, "ragOrchestrator", ragOrchestrator);

        ResponseEntity<OrchestrationResult> response = controller.query(request);

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(ragOrchestrator).orchestrate(eq("ok add it"), contextCaptor.capture());
        OrchestrationContext context = contextCaptor.getValue();
        Object rawHistory = context.getMetadata().get(ResolverChatHistoryEnrichmentStep.METADATA_KEY);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(context.getUserId()).isEqualTo("92");
        assertThat(context.getSessionId()).isEqualTo("session-1");
        assertThat(context.getConversationId()).isEqualTo("resolver-session-1");
        assertThat(rawHistory).isInstanceOf(List.class);
        assertThat((List<?>) rawHistory)
            .hasSize(2)
            .first()
            .satisfies(message -> {
                Map<?, ?> messageMap = (Map<?, ?>) message;
                assertThat(messageMap.get("role")).isEqualTo("user");
                assertThat(messageMap.get("content")).isEqualTo("Why can't I place an order?");
            });
    }

    @Test
    void confirmPendingActionExecutesStoredParamsForCurrentConversation() {
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        pendingActionStore.savePendingAction(
            "resolver-session-1",
            "92",
            new PendingAction(
                "update_payment_method",
                Map.of("last4", "4242"),
                "Use stored card ending in 4242?",
                Instant.now()
            )
        );

        when(actionRegistry.findHandler("update_payment_method")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any(ActionContext.class))).thenReturn(true);
        when(handler.executeAction(anyMap(), any(ActionContext.class))).thenReturn(ActionResult.builder()
            .success(true)
            .message("Payment method updated")
            .build());

        NaturalLanguageController controller = new NaturalLanguageController();
        ReflectionTestUtils.setField(controller, "actionRegistry", actionRegistry);
        ReflectionTestUtils.setField(controller, "pendingActionStore", pendingActionStore);

        ResponseEntity<ActionResult> response = controller.confirmPendingAction(Map.of(
            "userId", "92",
            "sessionId", "session-1",
            "conversationId", "resolver-session-1",
            "confirmed", true
        ));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        verify(handler).executeAction(eq(Map.of("last4", "4242")), any(ActionContext.class));
        assertThat(pendingActionStore.peekPendingAction("resolver-session-1", "92")).isEmpty();
    }
}
