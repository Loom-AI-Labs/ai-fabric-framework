package com.ai.fabric.realapps.agenticresolver.agentic;

import com.ai.fabric.realapps.agenticresolver.controller.AgenticResolverController;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:account-read-identity-integration;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.vector-db.type=memory",
    "app.demo.cleanup.enabled=false",
    "logging.level.ai.fabric=WARN"
})
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class AgenticResolverAccountReadIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgenticResolverSessionService sessions;

    @Autowired
    private AccountResolutionService accounts;

    @ParameterizedTest
    @CsvSource({
        "missing-payment, BLOCKED, VERIFIED_PAYMENT_METHOD",
        "missing-address, BLOCKED, VALIDATED_BILLING_ADDRESS",
        "ready-account, READY, NONE"
    })
    void chatReadsTheSessionBoundAccountWithoutMutatingIt(String scenario, String assessment, String requirement)
        throws Exception {
        String created = mockMvc.perform(post("/api/agentic-resolver/sessions"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(created).path("sessionId").asText();
        sessions.select(sessionId, scenario);
        AgenticResolverSessionService.ActiveSession active = sessions.active(sessionId);
        AccountResolutionService.AccountProfile before = accounts.accountProfile(active.subjectUserId());
        assertThat(active.conversationOwnerId()).isNotEqualTo(active.subjectUserId().toString());

        String body = mockMvc.perform(post("/api/agentic-resolver/chat")
                .header(AgenticResolverController.SESSION_HEADER, sessionId)
                .header(AgenticResolverController.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("question",
                    "Why can I not place an order? Check my account readiness and explain the blocker. Do not change anything."))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode result = objectMapper.readTree(body);
        assertThat(result.path("status").asText()).withFailMessage(body).isEqualTo("SUCCEEDED");
        assertThat(result.at("/output/assessment").asText()).isEqualTo(assessment);
        if ("NONE".equals(requirement)) {
            assertThat(result.at("/output/blockers")).isEmpty();
        } else {
            assertThat(result.at("/output/blockers"))
                .extracting(blocker -> blocker.path("requirement").asText()).containsExactly(requirement);
        }
        assertThat(accounts.accountProfile(active.subjectUserId())).isEqualTo(before);
    }
}
