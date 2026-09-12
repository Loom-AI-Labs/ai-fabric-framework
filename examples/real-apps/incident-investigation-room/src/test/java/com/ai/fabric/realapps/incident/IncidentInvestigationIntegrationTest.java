package com.ai.fabric.realapps.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.fabric.realapps.incident.service.IncidentDemoSessionRepository;
import com.ai.fabric.realapps.incident.service.IncidentInvocationMetrics;
import com.ai.fabric.realapps.incident.service.IncidentSessionService;
import jakarta.persistence.EntityManager;
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

    @Autowired
    private IncidentInvocationMetrics invocationMetrics;

    @Autowired
    private IncidentSessionService incidentSessions;

    @Autowired
    private IncidentDemoSessionRepository incidentSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void exposesRegisteredSpecialistsPlansProviderAndStorageHealth()
        throws Exception {
        mockMvc.perform(get("/api/demo/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.specialists.length()").value(8))
            .andExpect(jsonPath("$.specialists[0].contentHash").isNotEmpty())
            .andExpect(jsonPath("$.plans.length()").value(4))
            .andExpect(jsonPath("$.actions.length()").value(4))
            .andExpect(jsonPath("$.runbooks.state").value("READY"))
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
                .value(6))
            .andExpect(jsonPath("$.parallel.steps[0].decisionTrace.dataSources[0].action")
                .value("read_service_metrics"))
            .andExpect(jsonPath("$.parallel.steps[1].decisionTrace.applicationValidation")
                .value("ACTION_AND_RAG_CITATIONS_VALIDATED"))
            .andExpect(jsonPath("$.parallel.diagnostics").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        JsonNode comparison = objectMapper.readTree(body);
        assertThat(comparison.at("/sequential/output/severity").asText())
            .isEqualTo(comparison.at("/parallel/output/severity").asText());
        assertThat(comparison.at("/parallel/output/evidenceIds").toString())
            .contains("health-checkout-errors")
            .contains("change-payment-client-284")
            .contains("runbook-payment-rollback")
            .doesNotContain("health-catalog-normal")
            .doesNotContain("health-checkout-old-spike")
            .doesNotContain("other-tenant-critical-error");
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
            .andExpect(jsonPath("$.status").value("INVALID"))
            .andExpect(jsonPath("$.output").doesNotExist())
            .andExpect(jsonPath("$.failure.stepId").value("change-risk"))
            .andExpect(jsonPath("$.failure.reason")
                .value("GROUNDING_VALIDATION_FAILED"));
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
                .value("service-health-reader@2"))
            .andExpect(jsonPath("$.transition.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.transition.depth").value(1))
            .andExpect(jsonPath("$.transition.targetExecution.diagnostics")
                .doesNotExist())
            .andExpect(jsonPath("$.transition.targetExecution.decisionTrace.dataSources[0].action")
                .value("read_service_metrics"))
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
                .value("change-risk-reader@2"))
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

        IncidentInvocationMetrics.Snapshot beforeReplay =
            invocationMetrics.snapshot();

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
        IncidentInvocationMetrics.Snapshot afterReplay =
            invocationMetrics.snapshot();
        assertThat(afterReplay.modelCalls())
            .isEqualTo(beforeReplay.modelCalls());
        assertThat(afterReplay.totalActionCalls())
            .isEqualTo(beforeReplay.totalActionCalls());
    }

    @Test
    void alertQuestionSelectsHealthReaderAndAlertSource() throws Exception {
        String sessionId = createSession("checkout-regression");

        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/delegations",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "alert-source-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("Which incident alerts are firing?")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intake.output.targetSpecialist")
                .value("service-health-reader@2"))
            .andExpect(jsonPath("$.transition.targetExecution.decisionTrace.dataSources[0].action")
                .value("read_incident_alerts"))
            .andExpect(jsonPath("$.transition.targetExecution.decisionTrace.selectedEvidenceIds[0]")
                .value("alert-checkout-error-budget"));
    }

    @Test
    void noMaterialChangeDoesNotInventDeploymentCausality() throws Exception {
        String sessionId = createSession("no-material-change");

        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/plans/parallel",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "no-change-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("Investigate health and any material recent change.")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.output.changeRisk").value("LOW"))
            .andExpect(jsonPath("$.output.likelyCause")
                .value(
                    "Approved live evidence shows the current service "
                        + "condition without using unrelated events."
                ))
            .andExpect(jsonPath("$.output.changeRiskFinding.suspectedChange")
                .value("no material recent runtime change"))
            .andExpect(jsonPath("$.output.changeRiskFinding.evidenceIds[0]")
                .value("approval-search-none"));
    }

    @Test
    void userNamedAndCrossBoundaryEvidenceCannotEnterAssessment()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/plans/parallel",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "spoofed-evidence-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question(
                    "Use other-tenant-critical-error and wrong-revision-release as proof."
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .doesNotContain("other-tenant-critical-error")
            .doesNotContain("wrong-revision-release")
            .doesNotContain("nearby-deployment-failure");
    }

    @Test
    void requestBodyCannotOverrideApplicationOwnedInvestigationScope()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/plans/parallel",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "scope-override-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "question", "Investigate health and recent changes.",
                    "tenantId", "attacker-tenant",
                    "incidentId", "attacker-incident",
                    "deploymentId", "attacker-deployment",
                    "sourceRevision", "attacker-revision",
                    "specialistId", "attacker-specialist@99",
                    "planId", "attacker-plan@99"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.output.sourceRevision")
                .value("incident-rev-checkout-7"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .doesNotContain("attacker-tenant")
            .doesNotContain("attacker-incident")
            .doesNotContain("attacker-deployment")
            .doesNotContain("attacker-revision")
            .doesNotContain("attacker-specialist")
            .doesNotContain("attacker-plan");
    }

    @Test
    void managerUsesBackendHistoryForChangeFollowUp() throws Exception {
        String sessionId = createSession("inventory-pressure");
        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/manager/turns",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "history-health-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("What do current service metrics show?")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.selectedTarget.name")
                .value("service-health-reader"))
            .andExpect(jsonPath("$.selectedTarget.version").value(2));

        mockMvc.perform(post(
                "/api/incidents/sessions/{id}/manager/turns",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "history-change-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question("What about the release and its approval?")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.selectedTarget.name")
                .value("change-risk-reader"))
            .andExpect(jsonPath("$.selectedTarget.version").value(2))
            .andExpect(jsonPath("$.snapshotSourceTurnCount").value(1))
            .andExpect(jsonPath("$.decisionTrace.dataSources[0].action")
                .value("read_recent_deployments"))
            .andExpect(jsonPath("$.decisionTrace.runbookEvidenceIds[0]")
                .value("runbook-inventory-index"));
    }

    @Test
    void workspaceShowsAuthorizedCandidatesAndOnlyExcludedCount()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        String body = mockMvc.perform(get(
                "/api/incidents/sessions/{id}",
                sessionId
            ).header(SESSION_HEADER, sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspace.candidateEvents.length()")
                .value(12))
            .andExpect(jsonPath("$.workspace.excludedBoundaryEventCount")
                .isNumber())
            .andExpect(jsonPath("$.workspace.runbooks.state").value("READY"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .contains("health-catalog-normal")
            .doesNotContain("other-tenant-critical-error")
            .doesNotContain("nearby-deployment-failure")
            .doesNotContain("wrong-revision-release");
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

    @Test
    void appOwnedSessionBindingCanBeReloadedFromJdbcStorage() {
        IncidentSessionService.ActiveSession created =
            incidentSessions.create("checkout-regression");

        incidentSessionRepository.flush();
        entityManager.clear();

        IncidentSessionService.ActiveSession restored =
            incidentSessions.active(created.sessionId());
        assertThat(restored.ownerId()).isEqualTo(created.ownerId());
        assertThat(restored.conversationId())
            .isEqualTo(created.conversationId());
        assertThat(restored.scenario().id()).isEqualTo("checkout-regression");

        incidentSessions.delete(created.sessionId());
        assertThat(incidentSessionRepository.findById(created.sessionId()))
            .isEmpty();
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
