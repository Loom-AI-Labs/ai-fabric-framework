package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.fabric.realapps.agenticresolver.controller.AgenticResolverController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:account-chain-integration;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.vector-db.type=memory",
    "app.demo.cleanup.enabled=false",
    "logging.level.ai.fabric=WARN"
})
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class AccountSpecialistChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthProvesExactChainAndDurableCheckpointStore() throws Exception {
        mockMvc.perform(get("/api/demo/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.execution.specialistChainsReady")
                .value(true))
            .andExpect(jsonPath("$.execution.specialistChainDurability")
                .value("JDBC"))
            .andExpect(jsonPath("$.execution.specialistChains.length()")
                .value(1))
            .andExpect(jsonPath("$.execution.specialistChains[0].id")
                .value("account-smart-resolution@1"))
            .andExpect(jsonPath("$.execution.specialistChains[0].manager")
                .value("account-resolution-chain-manager@1"));
    }

    @Test
    void selectsOnlyAccountReadinessForAccountQuestion() throws Exception {
        String sessionId = createSession();
        JsonNode result = execute(
            sessionId,
            "account-only-1",
            "Inspect only my current account readiness and explain any "
                + "blockers. Do not assess a refund or account credit.",
            null,
            null
        );

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("results")).hasSize(1);
        assertThat(result.at("/results/0/specialist").asText())
            .isEqualTo("account-resolver-manager-read@1");
        assertThat(result.at("/results/0/facts/assessment").asText())
            .isEqualTo("BLOCKED");
        assertThat(result.path("timeline"))
            .extracting(step -> step.path("directiveType").asText())
            .containsExactly("INVOKE_ONE", "COMPLETE");
        assertThat(result.path("durable").asBoolean()).isTrue();
    }

    @Test
    void runsIndependentAccountAndBillingReadsInParallel() throws Exception {
        String sessionId = createSession();
        JsonNode result = execute(
            sessionId,
            "account-parallel-1",
            "Inspect both my account blockers and assess this refund.",
            "REFUND",
            new BigDecimal("75")
        );

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("results"))
            .extracting(value -> value.path("specialist").asText())
            .containsExactly(
                "account-resolver-manager-read@1",
                "billing-resolution-manager-advisor@1"
            );
        assertThat(result.at("/timeline/0/directiveType").asText())
            .isEqualTo("INVOKE_PARALLEL");
        assertThat(result.at("/timeline/0/parallelGroupId").asText())
            .isNotBlank();
        assertThat(result.at("/results/1/facts/decision").asText())
            .isEqualTo("REVIEW_REQUIRED");
        assertThat(result.at("/results/1/facts/expectedStatus").asText())
            .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void honorsExplicitAccountFirstAdaptiveSequence() throws Exception {
        String sessionId = createSession();
        JsonNode result = execute(
            sessionId,
            "account-sequential-1",
            "First inspect my current account readiness, then assess this "
                + "supplied refund. Do not run the checks in parallel.",
            "REFUND",
            new BigDecimal("75")
        );

        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("results"))
            .extracting(value -> value.path("specialist").asText())
            .containsExactly(
                "account-resolver-manager-read@1",
                "billing-resolution-manager-advisor@1"
            );
        assertThat(result.path("timeline"))
            .extracting(step -> step.path("directiveType").asText())
            .containsExactly("INVOKE_ONE", "INVOKE_ONE", "COMPLETE");
    }

    @Test
    void asksForMissingBillingFactsWithoutInvokingAWorker() throws Exception {
        String sessionId = createSession();
        JsonNode result = execute(
            sessionId,
            "account-clarify-1",
            "Assess a refund for me.",
            "REFUND",
            null
        );

        assertThat(result.path("status").asText()).isEqualTo("ASKED_USER");
        assertThat(result.path("results")).isEmpty();
        assertThat(result.path("message").asText())
            .containsIgnoringCase("amount");
        assertThat(result.at("/timeline/0/directiveType").asText())
            .isEqualTo("ASK_USER");
    }

    @Test
    void canCompleteWithoutWorkerAndUseTerminalReadOnlyHandoff()
        throws Exception {
        String scopeSession = createSession();
        JsonNode scope = execute(
            scopeSession,
            "account-scope-1",
            "What can you do?",
            null,
            null
        );
        assertThat(scope.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(scope.path("results")).isEmpty();
        assertThat(scope.path("timeline")).hasSize(1);

        String handoffSession = createSession();
        JsonNode handoff = execute(
            handoffSession,
            "account-handoff-1",
            "Handoff this refund assessment to the approved billing specialist.",
            "REFUND",
            new BigDecimal("25")
        );
        assertThat(handoff.path("status").asText()).isEqualTo("HANDED_OFF");
        assertThat(handoff.path("handoffTarget").asText())
            .isEqualTo("billing-resolution-manager-advisor@1");
        assertThat(handoff.path("results")).hasSize(1);
        assertThat(handoff.path("timeline")).hasSize(1);
    }

    @Test
    void terminalExecutionReplaysWithoutRepeatingWorkers() throws Exception {
        String sessionId = createSession();
        String key = "account-replay-1";
        JsonNode first = execute(
            sessionId,
            key,
            "Inspect my account blockers.",
            null,
            null
        );
        JsonNode replay = execute(
            sessionId,
            key,
            "Inspect my account blockers.",
            null,
            null
        );

        assertThat(replay.path("executionId").asText())
            .isEqualTo(first.path("executionId").asText());
        assertThat(replay.path("timeline")).isEqualTo(first.path("timeline"));
        assertThat(replay.path("replayed").asBoolean()).isTrue();
    }

    @Test
    void asyncExecutionExposesAuthorizedStatusAndTerminalResult()
        throws Exception {
        String sessionId = createSession();
        var request = objectMapper.createObjectNode();
        request.put(
            "question",
            "Inspect both my account blockers and assess this refund."
        );
        request.put("resolutionType", "REFUND");
        request.put("amount", 75);
        String submittedBody = mockMvc.perform(post(
                "/api/agentic-resolver/smart-resolutions/async"
            )
                .header(AgenticResolverController.SESSION_HEADER, sessionId)
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "account-async-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.executionId").isNotEmpty())
            .andExpect(jsonPath("$.durable").value(true))
            .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(submittedBody)
            .path("executionId").asText();

        JsonNode terminal = awaitExecution(sessionId, executionId);

        assertThat(terminal.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(terminal.at("/result/status").asText())
            .isEqualTo("COMPLETED");
        assertThat(terminal.at("/result/results")).hasSize(2);
        String anotherSession = createSession();
        mockMvc.perform(get(
                "/api/agentic-resolver/smart-resolutions/{executionId}",
                executionId
            ).header(
                AgenticResolverController.SESSION_HEADER,
                anotherSession
            ))
            .andExpect(status().isBadRequest());
    }

    @Test
    void asyncExecutionCanBeCancelledWithoutASubstituteAnswer()
        throws Exception {
        String sessionId = createSession();
        var request = objectMapper.createObjectNode();
        request.put(
            "question",
            "Inspect both my account blockers and assess this refund."
        );
        request.put("resolutionType", "REFUND");
        request.put("amount", 75);
        String submittedBody = mockMvc.perform(post(
                "/api/agentic-resolver/smart-resolutions/async"
            )
                .header(AgenticResolverController.SESSION_HEADER, sessionId)
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    "account-async-cancel-1"
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        String executionId = objectMapper.readTree(submittedBody)
            .path("executionId").asText();

        JsonNode beforeCancel = fetchStatus(sessionId, executionId);
        if (beforeCancel.path("status").asText().matches("QUEUED|RUNNING")) {
            String cancelledBody = mockMvc.perform(post(
                    "/api/agentic-resolver/smart-resolutions/{executionId}/cancel",
                    executionId
                ).header(
                    AgenticResolverController.SESSION_HEADER,
                    sessionId
                ))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            JsonNode cancelled = objectMapper.readTree(cancelledBody);
            assertThat(cancelled.path("status").asText())
                .isEqualTo("CANCELLED");
            assertThat(cancelled.at("/failure/reason").asText())
                .isEqualTo("CHAIN_CANCELLED");
            assertThat(cancelled.at("/result/status").asText())
                .isEqualTo("CANCELLED");
            assertThat(cancelled.at("/result/message").isMissingNode()
                || cancelled.at("/result/message").isNull()).isTrue();
            assertThat(cancelled.at("/result/results")).isEmpty();
        } else {
            assertThat(beforeCancel.path("status").asText())
                .isEqualTo("COMPLETED");
        }
    }

    @Test
    void rejectsCallerSuppliedIdentityAndUnknownFields() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/api/agentic-resolver/smart-resolutions")
                .header(AgenticResolverController.SESSION_HEADER, sessionId)
                .header(AgenticResolverController.IDEMPOTENCY_HEADER,
                    "account-identity-attack-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "question", "Inspect my account.",
                    "tenantId", "attacker-tenant",
                    "subjectUserId", "attacker-user"
                ))))
            .andExpect(status().isBadRequest());
    }

    private String createSession() throws Exception {
        String body = mockMvc.perform(post("/api/agentic-resolver/sessions"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("sessionId").asText();
    }

    private JsonNode execute(
        String sessionId,
        String idempotencyKey,
        String question,
        String resolutionType,
        BigDecimal amount
    ) throws Exception {
        var request = objectMapper.createObjectNode();
        request.put("question", question);
        if (resolutionType != null) {
            request.put("resolutionType", resolutionType);
        }
        if (amount != null) {
            request.put("amount", amount);
        }
        String body = mockMvc.perform(post(
                "/api/agentic-resolver/smart-resolutions"
            )
                .header(AgenticResolverController.SESSION_HEADER, sessionId)
                .header(
                    AgenticResolverController.IDEMPOTENCY_HEADER,
                    idempotencyKey
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode awaitExecution(
        String sessionId,
        String executionId
    ) throws Exception {
        JsonNode value = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            value = fetchStatus(sessionId, executionId);
            if (!value.path("status").asText().matches("QUEUED|RUNNING")) {
                return value;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError(
            "Smart account resolution did not finish: " + value
        );
    }

    private JsonNode fetchStatus(
        String sessionId,
        String executionId
    ) throws Exception {
        String body = mockMvc.perform(get(
                "/api/agentic-resolver/smart-resolutions/{executionId}",
                executionId
            ).header(
                AgenticResolverController.SESSION_HEADER,
                sessionId
            ))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
