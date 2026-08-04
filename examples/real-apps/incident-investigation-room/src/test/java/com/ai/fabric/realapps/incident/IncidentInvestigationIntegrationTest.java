package com.ai.fabric.realapps.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:incident-integration;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class IncidentInvestigationIntegrationTest {

    private static final String SESSION_HEADER =
        "X-AI-Fabric-Demo-Session";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesRegisteredSpecialistsPlansProviderAndStorageHealth()
        throws Exception {
        mockMvc.perform(get("/api/demo/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.specialists.length()").value(4))
            .andExpect(jsonPath("$.specialists[0].contentHash").isNotEmpty())
            .andExpect(jsonPath("$.plans.length()").value(2))
            .andExpect(jsonPath("$.provider.generation")
                .value("incident-smoke"))
            .andExpect(jsonPath("$.provider.ready").value(true))
            .andExpect(jsonPath("$.storage.domain").value("UP"));
    }

    @Test
    void sequentialAndParallelPlansProduceEquivalentTypedAssessment()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/compare",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "plan-parity-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("Investigate the checkout regression.")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sequential.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.parallel.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.semanticallyEquivalent").value(true))
            .andExpect(jsonPath("$.parallel.steps[0].parallelGroupId")
                .value("independent-readers"))
            .andExpect(jsonPath("$.parallel.output.sourceRevision")
                .value("incident-rev-checkout-7"))
            .andExpect(jsonPath("$.parallel.output.evidenceIds.length()")
                .value(2))
            .andReturn().getResponse().getContentAsString();

        JsonNode comparison = objectMapper.readTree(body);
        assertThat(comparison.at("/sequential/output/severity").asText())
            .isEqualTo(comparison.at("/parallel/output/severity").asText());
    }

    @Test
    void allRequiredParallelPlanReturnsNoPartialAssessmentWhenBranchFails()
        throws Exception {
        String sessionId = createSession("branch-failure");
        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/plans/parallel",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "branch-failure-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("Run the full controlled investigation.")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.output").doesNotExist())
            .andExpect(jsonPath("$.failure.stepId").value("change-risk"));
    }

    @Test
    void delegationAndHandoffAreBoundToOneApprovedTransition()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/delegations",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "delegation-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("Check current service latency and errors.")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intake.output.targetSpecialist")
                .value("service-health-reader@1"))
            .andExpect(jsonPath("$.transition.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.transition.depth").value(1))
            .andExpect(jsonPath("$.secondTransitionCanary.status")
                .value("DENIED"))
            .andExpect(jsonPath("$.secondTransitionCanary.failure.reason")
                .value("DELEGATION_DEPTH_EXCEEDED"));

        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/handoffs",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "handoff-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("Inspect the payment release and rollback runbook.")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intake.output.targetSpecialist")
                .value("change-risk-reader@1"))
            .andExpect(jsonPath("$.transition.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.secondTransitionCanary.status")
                .value("DENIED"));
    }

    @Test
    void conversationManagerUsesBackendHistoryAndReplaysSameTurn()
        throws Exception {
        String sessionId = createSession("inventory-pressure");
        String first = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/manager/turns",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "manager-turn-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("What do current service metrics show?")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SPECIALIST_RESULT"))
            .andExpect(jsonPath("$.selectedTarget.name")
                .value("service-health-reader"))
            .andExpect(jsonPath("$.snapshotSourceTurnCount").value(0))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn().getResponse().getContentAsString();

        String replay = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/manager/turns",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "manager-turn-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("What do current service metrics show?")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true))
            .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(replay).path("turnId").asText())
            .isEqualTo(objectMapper.readTree(first).path("turnId").asText());
    }

    @Test
    void sessionTokenMismatchAndResetFailClosed() throws Exception {
        String first = createSession("checkout-regression");
        String second = createSession("inventory-pressure");

        mockMvc.perform(get("/api/incidents/sessions/{id}", first)
                .header(SESSION_HEADER, second))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value("Incident demo session access was denied"));

        String resetBody = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/reset",
                first
            ).header(SESSION_HEADER, first))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String replacement = objectMapper.readTree(resetBody)
            .path("sessionId").asText();
        assertThat(replacement).isNotEqualTo(first);

        mockMvc.perform(get("/api/incidents/sessions/{id}", first)
                .header(SESSION_HEADER, first))
            .andExpect(status().isBadRequest());
    }

    private String createSession(String scenarioId) throws Exception {
        String body = mockMvc.perform(post("/api/incidents/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenarioId\":\"" + scenarioId + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("sessionId").asText();
    }

    private String question(String value) throws Exception {
        return objectMapper.writeValueAsString(
            java.util.Map.of("question", value)
        );
    }
}
