package com.ai.fabric.realapps.deploymentguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.RAGRequest;
import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.spi.RAGProvider;
import com.ai.fabric.realapps.deploymentguard.service.DeploymentKnowledgeExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class DeploymentKnowledgeGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private SpecialistRegistry specialistRegistry;

    @Autowired
    private EntityAccessPolicy entityAccessPolicy;

    @Test
    void loadsManifestAndRetrievesOnlyTrustedTenantDeploymentEvidence() {
        assertThat(specialistRegistry.find(
            DeploymentKnowledgeExecutionService.SPECIALIST_ID
        )).isPresent();

        var response = ragProvider.performRag(RAGRequest.builder()
            .query("What is the current deployment status and incident?")
            .entityType("deployment-knowledge")
            .limit(5)
            .threshold(0.0)
            .filters(java.util.Map.of(
                "tenantId", "orbit",
                "deploymentId", "checkout-edge"
            ))
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("operator-1")
                .tenantId("northstar")
                .deploymentId("payments-prod")
                .build())
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).isNotEmpty();
        assertThat(response.getDocuments())
            .allSatisfy(document -> {
                assertThat(document.getId()).startsWith(
                    "northstar-payments-prod-"
                );
                assertThat(document.getMetadata())
                    .containsEntry("tenantId", "northstar")
                    .containsEntry("deploymentId", "payments-prod");
            });
    }

    @Test
    void browserSessionCanSwitchApprovedContextWithoutSupplyingIdentityFields()
        throws Exception {
        String body = mockMvc.perform(post("/api/deployment-guard/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeContextId").value("northstar-payments"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode session = objectMapper.readTree(body);
        String sessionId = session.path("sessionId").asText();

        mockMvc.perform(get("/api/deployment-guard/evidence")
                .header("X-AI-Fabric-Demo-Session", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(
                org.hamcrest.Matchers.startsWith("northstar-payments-prod-")
            ));

        mockMvc.perform(put(
                "/api/deployment-guard/sessions/current/contexts/orbit-checkout"
            ).header("X-AI-Fabric-Demo-Session", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeContextId").value("orbit-checkout"));

        mockMvc.perform(get("/api/deployment-guard/evidence")
                .header("X-AI-Fabric-Demo-Session", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(
                org.hamcrest.Matchers.startsWith("orbit-checkout-edge-")
            ));

        mockMvc.perform(get(
                "/api/deployment-guard/evidence/northstar-payments-prod-status"
            ).header("X-AI-Fabric-Demo-Session", sessionId))
            .andExpect(status().isBadRequest());
    }

    @Test
    void registersStrictReadOnlyAccessPolicyForApprovedDeploymentContexts() {
        AIAccessSubjectContext trusted = AIAccessSubjectContext.builder()
            .subjectId("demo-operator-1")
            .authMode("TRUSTED_APPLICATION")
            .callerType("SERVICE")
            .tenantId("northstar")
            .deploymentId("payments-prod")
            .grantedScopes(java.util.List.of(
                "specialist:deployment-knowledge-reader@1",
                "vector:deployment-knowledge"
            ))
            .build();
        java.util.Map<String, Object> read = java.util.Map.of(
            "resourceId", "rag:intent",
            "operationType", "READ"
        );

        assertThat(entityAccessPolicy.canAccess(trusted, read)).isTrue();
        assertThat(entityAccessPolicy.canAccess(trusted, java.util.Map.of(
            "resourceId", "rag:intent",
            "operationType", "WRITE"
        ))).isFalse();
        assertThat(entityAccessPolicy.canAccess(
            AIAccessSubjectContext.builder()
                .subjectId("demo-operator-1")
                .authMode("TRUSTED_APPLICATION")
                .callerType("SERVICE")
                .tenantId("unknown")
                .deploymentId("payments-prod")
                .grantedScopes(java.util.List.of(
                    "specialist:deployment-knowledge-reader@1"
                ))
                .build(),
            read
        )).isFalse();
    }

    @Test
    void missingVectorScopeCanaryFailsClosedWithoutAnswerOrEvidence()
        throws Exception {
        String sessionBody = mockMvc.perform(post("/api/deployment-guard/sessions"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String sessionId = objectMapper.readTree(sessionBody)
            .path("sessionId")
            .asText();

        String responseBody = mockMvc.perform(post(
                "/api/deployment-guard/canaries/missing-scope"
            ).header("X-AI-Fabric-Demo-Session", sessionId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode response = objectMapper.readTree(responseBody);

        assertThat(response.path("status").asText()).isNotEqualTo("SUCCEEDED");
        assertThat(response.path("answer").isNull()).isTrue();
        assertThat(response.path("evidence")).isEmpty();
        assertThat(response.path("failure").isObject()).isTrue();
        assertThat(response.path("boundary").path("enforced").asBoolean())
            .isTrue();
    }
}
