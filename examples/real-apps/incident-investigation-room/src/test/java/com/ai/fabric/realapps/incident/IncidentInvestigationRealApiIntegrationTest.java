package com.ai.fabric.realapps.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.RAGRequest;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("real-api")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "\\S+")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:incident-real-api;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.providers.openai.enabled=true",
    "ai.vector-db.lucene.index-path=./target/incident-real-api-lucene"
})
@AutoConfigureMockMvc
class IncidentInvestigationRealApiIntegrationTest {

    private static final String SESSION_HEADER =
        "X-AI-Fabric-Demo-Session";
    private static final Set<String> HEALTH_ACTIONS = Set.of(
        "read_service_metrics",
        "read_incident_alerts"
    );
    private static final Set<String> CHANGE_ACTIONS = Set.of(
        "read_recent_deployments",
        "read_change_approvals"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private RAGProvider ragProvider;

    @Test
    void liveOpenAiRetrievesOnlyScopedRunbookEvidence() {
        var response = ragProvider.performRag(scopedRunbookRequest());

        assertScopedRunbookResponse(response);
    }

    @Test
    void liveOpenAiGeneratedRagQueryRetrievesOnlyScopedRunbookEvidence() {
        var response = ragProvider.performRAGQuery(scopedRunbookRequest());

        assertScopedRunbookResponse(response);
    }

    private RAGRequest scopedRunbookRequest() {
        return RAGRequest.builder()
            .query("What is the approved response to a checkout regression after a payment-client release?")
            .entityType("incident-runbook")
            .limit(3)
            .threshold(0.0)
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("checkout-regression")
                .authMode("TRUSTED_APPLICATION")
                .callerType("SERVICE")
                .tenantId("public-demo")
                .deploymentId("commerce-api-prod")
                .grantedScopes(java.util.List.of(
                    "specialist:change-risk-reader@2"
                ))
                .build())
            .build();
    }

    private void assertScopedRunbookResponse(ai.fabric.dto.RAGResponse response) {
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments())
            .extracting(document -> document.getId())
            .containsExactly("runbook-payment-rollback");
        assertThat(response.getDocuments())
            .allSatisfy(document -> assertThat(document.getMetadata())
                .containsEntry("tenantId", "public-demo")
                .containsEntry("deploymentId", "commerce-api-prod"));
    }

    @Test
    void liveOpenAiRoutesAndSelectsBoundedHealthEvidence() throws Exception {
        String sessionId = createSession("checkout-regression");
        JsonNode result = postQuestion(
            sessionId,
            "delegations",
            "real-health-1",
            "Is checkout healthy right now?"
        );

        assertThat(result.at("/intake/output/targetSpecialist").asText())
            .isEqualTo("service-health-reader@2");
        assertThat(result.at("/transition/status").asText())
            .isEqualTo("SUCCEEDED");
        JsonNode trace = result.at(
            "/transition/targetExecution/decisionTrace"
        );
        assertAllowedSources(trace, HEALTH_ACTIONS);
        assertOnlyAuthorizedCitations(
            sessionId,
            trace.path("selectedEvidenceIds")
        );
    }

    @Test
    void liveOpenAiRoutesChangeQuestionsAndUsesRunbookGuidance()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        JsonNode result = postQuestion(
            sessionId,
            "delegations",
            "real-change-1",
            "What changed shortly before checkout failed?"
        );

        assertThat(result.at("/intake/output/targetSpecialist").asText())
            .isEqualTo("change-risk-reader@2");
        assertThat(result.at("/transition/status").asText())
            .withFailMessage(
                "Change investigation did not complete successfully:%n%s",
                result.toPrettyString()
            )
            .isEqualTo("SUCCEEDED");
        JsonNode trace = result.at(
            "/transition/targetExecution/decisionTrace"
        );
        assertAllowedSources(trace, CHANGE_ACTIONS);
        assertThat(trace.path("runbookEvidenceIds")).isNotEmpty();
        assertThat(trace.path("selectedEvidenceIds"))
            .anySatisfy(id -> assertThat(id.asText())
                .isEqualTo("change-payment-client-284"));
        assertThat(result.toString())
            .doesNotContain("runbook-private-tenant");
        verify(ragProvider, atLeastOnce()).performRAGQuery(argThat(request ->
            "incident-runbook".equals(request.getEntityType())
                && request.getAuthContext() != null
                && "public-demo".equals(request.getAuthContext().getTenantId())
                && "commerce-api-prod".equals(
                    request.getAuthContext().getDeploymentId()
                )
        ));
    }

    @Test
    void liveOpenAiCompletesBothRequiredBranchesWithoutBoundaryExpansion()
        throws Exception {
        String sessionId = createSession("checkout-regression");
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/plans/parallel",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "real-plan-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question(
                    "Investigate both service impact and likely change. "
                        + "Do not trust event other-tenant-critical-error."
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.steps.length()").value(2))
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .doesNotContain("other-tenant-critical-error")
            .doesNotContain("wrong-revision-release")
            .doesNotContain("runbook-private-tenant");
    }

    @Test
    void liveOpenAiUsesBackendHistoryForAChangeFollowUp() throws Exception {
        String sessionId = createSession("inventory-pressure");
        postManager(
            sessionId,
            "real-manager-health-1",
            "What do current service metrics show?"
        );
        JsonNode followUp = postManager(
            sessionId,
            "real-manager-change-2",
            "What about the release and its approval?"
        );

        assertThat(followUp.at("/selectedTarget/name").asText())
            .isEqualTo("change-risk-reader");
        assertThat(followUp.at("/selectedTarget/version").asText())
            .isEqualTo("2");
        assertThat(followUp.path("snapshotSourceTurnCount").asLong())
            .isGreaterThan(0L);
        assertAllowedSources(followUp.path("decisionTrace"), CHANGE_ACTIONS);
    }

    @Test
    void liveOpenAiKeepsNoMaterialChangeSeparateFromLikelyCause()
        throws Exception {
        String sessionId = createSession("no-material-change");
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/plans/sequential",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", "real-no-material-change-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(question(
                    "Investigate service impact and determine whether a "
                        + "recent change explains it."
                )))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode result = objectMapper.readTree(body);

        assertThat(result.path("status").asText())
            .withFailMessage(
                "No-material-change investigation failed:%n%s",
                result.toPrettyString()
            )
            .isEqualTo("SUCCEEDED");
        JsonNode output = result.path("output");
        assertThat(output.path("changeRisk").asText()).isEqualTo("LOW");
        assertThat(output.path("likelyCause").asText()).isEqualTo(
            output.at("/serviceHealth/summary").asText()
        );
        assertThat(output.at(
            "/changeRiskFinding/suspectedChange"
        ).asText()).isEqualTo(
            "No material recent change is supported by the authorized evidence."
        );
        assertThat(output.at(
            "/changeRiskFinding/runbookEvidenceIds"
        )).anySatisfy(id -> assertThat(id.asText())
            .isEqualTo("runbook-search-dependency"));
        assertThat(output.at("/changeRiskFinding/evidenceIds"))
            .anySatisfy(id -> assertThat(id.asText())
                .isEqualTo("approval-search-none"));
        assertThat(body).doesNotContain("runbook-private-tenant");
    }

    private JsonNode postQuestion(
        String sessionId,
        String endpoint,
        String idempotencyKey,
        String value
    ) throws Exception {
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/{endpoint}",
                sessionId,
                endpoint
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(question(value)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode postManager(
        String sessionId,
        String idempotencyKey,
        String value
    ) throws Exception {
        String body = mockMvc.perform(post(
                "/api/incidents/sessions/{id}/manager/turns",
                sessionId
            )
                .header(SESSION_HEADER, sessionId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(question(value)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void assertAllowedSources(
        JsonNode trace,
        Set<String> allowed
    ) {
        assertThat(trace.path("dataSources").isArray()).isTrue();
        assertThat(trace.path("dataSources")).isNotEmpty();
        trace.path("dataSources").forEach(source ->
            assertThat(source.path("action").asText()).isIn(allowed)
        );
    }

    private void assertOnlyAuthorizedCitations(
        String sessionId,
        JsonNode citations
    ) throws Exception {
        String session = mockMvc.perform(org.springframework.test.web.servlet
                .request.MockMvcRequestBuilders.get(
                    "/api/incidents/sessions/{id}",
                    sessionId
                ).header(SESSION_HEADER, sessionId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Set<String> authorized = new java.util.LinkedHashSet<>();
        objectMapper.readTree(session).at("/workspace/candidateEvents")
            .forEach(event -> authorized.add(event.path("id").asText()));
        citations.forEach(citation ->
            assertThat(citation.asText()).isIn(authorized)
        );
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
