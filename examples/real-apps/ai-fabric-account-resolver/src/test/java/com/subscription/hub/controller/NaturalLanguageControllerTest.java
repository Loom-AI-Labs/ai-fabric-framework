package com.subscription.hub.controller;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.PendingAction;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
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
