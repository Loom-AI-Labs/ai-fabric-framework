package com.ai.fabric.realapps.agenticresolver.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import com.ai.fabric.realapps.agenticresolver.agentic.event.ProactiveAccountEventService;
import com.ai.fabric.realapps.agenticresolver.agentic.event.ProactiveEventSubmission;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProactiveAccountEventController.class)
class ProactiveAccountEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProactiveAccountEventService eventService;

    @Test
    void acceptsRawPaymentEventWithoutPublicIdentityFields()
        throws Exception {
        ExecutionHandle handle = new ExecutionHandle(
            "exec-event-1",
            ExecutionDurability.DURABLE,
            ExecutionHandleStatus.QUEUED,
            Instant.parse("2026-07-29T10:01:00Z"),
            Instant.parse("2026-07-29T10:05:00Z"),
            null
        );
        when(eventService.submit(eq("session-1"), any()))
            .thenReturn(new ProactiveEventSubmission(
                "payment-event-1",
                ProactiveAccountEventService.EVENT_TYPE,
                handle
            ));

        mockMvc.perform(post(
                "/api/agentic-resolver/events/payment-verification-failed"
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "eventId": "payment-event-1",
                      "failureCode": "DECLINED",
                      "attemptNumber": 2,
                      "occurredAt": "2026-07-28T10:00:00Z"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.eventId").value("payment-event-1"))
            .andExpect(jsonPath("$.eventType")
                .value("PAYMENT_VERIFICATION_FAILED"))
            .andExpect(jsonPath("$.execution.invocationId")
                .value("exec-event-1"))
            .andExpect(jsonPath("$.execution.durability")
                .value("DURABLE"));

        verify(eventService).submit(eq("session-1"), any());
    }

    @Test
    void rejectsInvalidEventBeforeCallingTheService() throws Exception {
        mockMvc.perform(post(
                "/api/agentic-resolver/events/payment-verification-failed"
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "eventId": "invalid event id",
                      "failureCode": "DECLINED",
                      "attemptNumber": 0,
                      "occurredAt": "2026-07-28T10:00:00Z"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesExecutionsOutsideTheCurrentEventContext() throws Exception {
        when(eventService.find("session-2", "exec-event-1"))
            .thenReturn(Optional.empty());

        mockMvc.perform(get(
                "/api/agentic-resolver/events/executions/exec-event-1"
            )
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-2"
                ))
            .andExpect(status().isNotFound());
    }
}
