package com.ai.fabric.realapps.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:handover-test;DB_CLOSE_DELAY=-1",
    "app.demo.operator-access-code=test-operator-code",
    "app.demo.public-operator.enabled=true",
    "app.demo.public-operator.access-code=demo-handover",
    "app.cors.allowed-origins=https://ai-fabric.dev",
    "app.cors.allow-credentials=true",
    "ai.enabled=false"
})
@AutoConfigureMockMvc
class ConversationHandoverDemoHttpTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RequestConversationHandoverAction handoverAction;

    @MockitoBean RAGOrchestrator orchestrator;

    @BeforeEach
    void modelAnswersOrdinaryTurns() {
        when(orchestrator.orchestrate(anyString(), any(OrchestrationContext.class)))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("The AI assistant is available.")
                .build());
    }

    @Test
    void frameworkOwnsTheCompleteAiHumanAiAuthorityLifecycle() throws Exception {
        MvcResult created = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation.control.state").value("AI_ACTIVE"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) created.getRequest().getSession(false);
        JsonNode initial = json(created).path("conversation");
        String conversationId = json(created).path("conversationId").asText();

        JsonNode aiTurn = postJson(
            session,
            "/api/demo/conversations/" + conversationId + "/messages",
            "{\"text\":\"Hello\",\"clientMessageId\":\"user-1\"}"
        );
        assertThat(roleCount(aiTurn, "AI")).isEqualTo(1);

        JsonNode pending = postJson(
            session,
            "/api/demo/conversations/" + conversationId + "/handover",
            handover(initial.path("control").path("version").asLong(), "request-human-1")
        );
        assertThat(pending.path("control").path("state").asText()).isEqualTo("HUMAN_PENDING");

        JsonNode pendingMessage = postJson(
            session,
            "/api/demo/conversations/" + conversationId + "/messages",
            "{\"text\":\"I am still here\",\"clientMessageId\":\"user-2\"}"
        );
        assertThat(roleCount(pendingMessage, "AI")).isEqualTo(1);

        mvc.perform(post("/api/demo/operator/session")
                .session(session)
                .contentType("application/json")
                .content("{\"accessCode\":\"test-operator-code\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true));

        JsonNode active = postJson(
            session,
            "/api/demo/operator/conversations/" + conversationId + "/claim",
            transition(pending.path("control").path("version").asLong(), "claim-1")
        );
        assertThat(active.path("control").path("state").asText()).isEqualTo("HUMAN_ACTIVE");

        JsonNode humanReply = postJson(
            session,
            "/api/demo/operator/conversations/" + conversationId + "/messages",
            "{\"text\":\"A human is now helping.\",\"clientMessageId\":\"human-1\"}"
        );
        assertThat(roleCount(humanReply, "HUMAN")).isEqualTo(1);

        JsonNode humanActiveMessage = postJson(
            session,
            "/api/demo/conversations/" + conversationId + "/messages",
            "{\"text\":\"Thank you\",\"clientMessageId\":\"user-3\"}"
        );
        assertThat(roleCount(humanActiveMessage, "AI")).isEqualTo(1);

        JsonNode resumed = postJson(
            session,
            "/api/demo/operator/conversations/" + conversationId + "/return-to-ai",
            transition(active.path("control").path("version").asLong(), "return-1")
        );
        assertThat(resumed.path("control").path("state").asText()).isEqualTo("AI_ACTIVE");
        assertThat(resumed.path("control").path("controlEpoch").asLong()).isEqualTo(3);

        JsonNode resumedAiTurn = postJson(
            session,
            "/api/demo/conversations/" + conversationId + "/messages",
            "{\"text\":\"Are you back?\",\"clientMessageId\":\"user-4\"}"
        );
        assertThat(roleCount(resumedAiTurn, "AI")).isEqualTo(2);
    }

    @Test
    void ownerAndOperatorBoundariesFailClosed() throws Exception {
        MvcResult created = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andReturn();
        String conversationId = json(created).path("conversationId").asText();

        mvc.perform(get("/api/demo/conversations/{id}", conversationId).session(new MockHttpSession()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/demo/operator/conversations").session(new MockHttpSession()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAdvertisesOnlyThePublicDemoCode() throws Exception {
        mvc.perform(get("/api/demo/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicOperatorEnabled").value(true))
            .andExpect(jsonPath("$.demoOperatorAccessCode").value("demo-handover"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("test-operator-code"));
    }

    @Test
    void publicOperatorCanRunOwnLifecycleButCannotAccessOtherVisitors() throws Exception {
        MvcResult first = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andReturn();
        MvcResult second = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        MockHttpSession otherSession = (MockHttpSession) second.getRequest().getSession(false);
        String ownId = json(first).path("conversationId").asText();
        String otherId = json(second).path("conversationId").asText();
        JsonNode pending = postJson(session, "/api/demo/conversations/" + ownId + "/handover", handover(0, "own-public-intake"));
        postJson(otherSession, "/api/demo/conversations/" + otherId + "/handover", handover(0, "other-public-intake"));

        mvc.perform(post("/api/demo/operator/session").session(session)
                .contentType("application/json").content("{\"accessCode\":\"demo-handover\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/demo/operator/conversations").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].conversationId").value(ownId));
        mvc.perform(get("/api/demo/operator/conversations/{id}", otherId).session(session))
            .andExpect(status().isForbidden());
        for (String operation : new String[] {"claim", "return-to-ai", "close"}) {
            mvc.perform(post("/api/demo/operator/conversations/{id}/{operation}", otherId, operation)
                    .session(session).contentType("application/json").content(transition(1, "foreign-" + operation)))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/demo/operator/conversations/{id}/messages", otherId).session(session)
                .contentType("application/json").content("{\"text\":\"Not authorized\",\"clientMessageId\":\"foreign-reply\"}"))
            .andExpect(status().isForbidden());

        JsonNode claimed = postJson(session, "/api/demo/operator/conversations/" + ownId + "/claim",
            transition(pending.path("control").path("version").asLong(), "public-claim"));
        assertThat(claimed.path("control").path("state").asText()).isEqualTo("HUMAN_ACTIVE");
        JsonNode reply = postJson(session, "/api/demo/operator/conversations/" + ownId + "/messages",
            "{\"text\":\"A human is helping.\",\"clientMessageId\":\"public-reply\"}");
        assertThat(roleCount(reply, "HUMAN")).isEqualTo(1);
        JsonNode resumed = postJson(session, "/api/demo/operator/conversations/" + ownId + "/return-to-ai",
            transition(claimed.path("control").path("version").asLong(), "public-return"));
        assertThat(resumed.path("control").path("state").asText()).isEqualTo("AI_ACTIVE");
        mvc.perform(get("/api/demo/conversations/{id}", otherId).session(otherSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.control.state").value("HUMAN_PENDING"));

        mvc.perform(post("/api/demo/operator/session").session(session)
                .contentType("application/json").content("{\"accessCode\":\"test-operator-code\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/demo/operator/conversations/{id}", otherId).session(session))
            .andExpect(status().isOk());
    }

    @Test
    void publicOperatorRequiresAnExistingVisitorSession() throws Exception {
        mvc.perform(post("/api/demo/operator/session").session(new MockHttpSession())
                .contentType("application/json").content("{\"accessCode\":\"demo-handover\"}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/demo/operator/conversations").session(new MockHttpSession()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/demo/operator/session").session(new MockHttpSession())
                .contentType("application/json").content("{\"accessCode\":\"wrong-code\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void publicWebsiteCanUseCredentialedDemoSessions() throws Exception {
        mvc.perform(options("/api/demo/session")
                .header("Origin", "https://ai-fabric.dev")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://ai-fabric.dev"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void governedActionRequestsRequiredIntakeWithoutChangingConversationAuthority() throws Exception {
        MvcResult created = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) created.getRequest().getSession(false);
        String conversationId = json(created).path("conversationId").asText();
        String ownerId = String.valueOf(session.getAttribute("demo.owner"));
        ActionContext context = new ActionContext(
            OrchestrationContext.builder()
                .requestId("action-test-" + UUID.randomUUID())
                .sessionId(ownerId)
                .conversationId(conversationId)
                .mode("handover")
                .position("conversation")
                .build(),
            null
        );

        assertThat(handoverAction.allowed(context)).isTrue();
        assertThat(RequestConversationHandoverAction.class.getAnnotation(AIAction.class).anonymousAllowed()).isTrue();
        assertThat(handoverAction.confirmationRequired(HandoverRequestProvenance.EXPLICIT_USER_REQUEST)).isFalse();
        assertThat(handoverAction.confirmationRequired(HandoverRequestProvenance.MODEL_RECOMMENDATION)).isTrue();

        ActionResult result = handoverAction.execute(
            HandoverRequestProvenance.EXPLICIT_USER_REQUEST,
            context
        );

        assertThat(result.isSuccess()).isTrue();
        mvc.perform(get("/api/demo/conversations/{id}", conversationId).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.control.state").value("AI_ACTIVE"))
            .andExpect(jsonPath("$.handoverIntakeRequired").value(true))
            .andExpect(jsonPath("$.handoverIntakeSchema.anonymousAllowed").value(false))
            .andExpect(jsonPath("$.handoverIntakeSchema.fields[0].key").value("email"))
            .andExpect(jsonPath("$.handoverIntakeSchema.fields[0].required").value(true))
            .andExpect(jsonPath("$.messages[1].metadata.action").value("request_conversation_handover"))
            .andExpect(jsonPath("$.messages[1].metadata.selectedBy").value("AI_FABRIC_ORCHESTRATOR"))
            .andExpect(jsonPath("$.messages[1].metadata.outcome").value("HANDOVER_INTAKE_REQUIRED"));

        JsonNode pending = postJson(
            session,
            "/api/demo/conversations/" + conversationId + "/handover",
            handover(0, "action-intake-submit")
        );
        assertThat(pending.path("control").path("state").asText()).isEqualTo("HUMAN_PENDING");
        assertThat(pending.path("handoverIntakeRequired").asBoolean()).isFalse();
        assertThat(pending.path("contactIntake").path("email").asText()).isEqualTo("visitor@example.test");
    }

    @Test
    void requiredEmailAndConsentFailClosedBeforeTheControlTransition() throws Exception {
        MvcResult created = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) created.getRequest().getSession(false);
        String conversationId = json(created).path("conversationId").asText();

        mvc.perform(post("/api/demo/conversations/{id}/handover", conversationId)
                .session(session)
                .contentType("application/json")
                .content("""
                    {
                      "expectedVersion": 0,
                      "idempotencyKey": "missing-required-email",
                      "intake": {"name": "Demo Visitor"},
                      "consentedFields": []
                    }
                    """))
            .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/demo/conversations/{id}", conversationId).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.control.state").value("AI_ACTIVE"))
            .andExpect(jsonPath("$.contactIntake").doesNotExist());
    }

    @Test
    void transitionReplayIsIdempotentAndStaleVersionIsVisible() throws Exception {
        MvcResult created = mvc.perform(post("/api/demo/session").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) created.getRequest().getSession(false);
        String conversationId = json(created).path("conversationId").asText();
        String body = handover(0, "request-replay-" + UUID.randomUUID());

        postJson(session, "/api/demo/conversations/" + conversationId + "/handover", body);
        mvc.perform(post("/api/demo/conversations/{id}/handover", conversationId)
                .session(session)
                .contentType("application/json")
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.control.state").value("HUMAN_PENDING"));
    }

    private JsonNode postJson(MockHttpSession session, String path, String body) throws Exception {
        return json(mvc.perform(post(path)
                .session(session)
                .contentType("application/json")
                .content(body))
            .andExpect(status().isOk())
            .andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String transition(long expectedVersion, String idempotencyKey) {
        return "{\"expectedVersion\":" + expectedVersion + ",\"idempotencyKey\":\"" + idempotencyKey + "\"}";
    }

    private String handover(long expectedVersion, String idempotencyKey) {
        return """
            {
              "expectedVersion": %d,
              "idempotencyKey": "%s",
              "intake": {
                "email": "visitor@example.test",
                "name": "Demo Visitor",
                "phone": "+44 20 7946 0958"
              },
              "consentedFields": ["email", "phone"]
            }
            """.formatted(expectedVersion, idempotencyKey);
    }

    private long roleCount(JsonNode view, String role) {
        long count = 0;
        for (JsonNode message : view.path("messages")) {
            if (role.equals(message.path("role").asText())) count++;
        }
        return count;
    }
}
