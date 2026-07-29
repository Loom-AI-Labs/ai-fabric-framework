package com.ai.fabric.realapps.agenticresolver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverExecutionService;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.PlanInputResumeRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgenticResolverController.class)
class AgenticResolverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgenticResolverSessionService sessionService;

    @MockitoBean
    private AgenticResolverExecutionService executionService;

    @Test
    void deserializesTypedPlanResumeResponse() throws Exception {
        mockMvc.perform(post("/api/agentic-resolver/plans/input/resume")
                .header(
                    AgenticResolverController.SESSION_HEADER,
                    "session-1"
                )
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "resume-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "executionId": "plan-execution-1",
                      "requestId": "input-request-1",
                      "response": {
                        "amount": 75
                      }
                    }
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<PlanInputResumeRequest> request =
            ArgumentCaptor.forClass(PlanInputResumeRequest.class);
        verify(executionService).resumeAccountBillingPlan(
            eq("session-1"),
            request.capture(),
            eq("resume-1")
        );
        assertThat(request.getValue().executionId())
            .isEqualTo("plan-execution-1");
        assertThat(request.getValue().requestId())
            .isEqualTo("input-request-1");
        assertThat(request.getValue().response().amount())
            .isEqualByComparingTo(new BigDecimal("75"));
    }
}
