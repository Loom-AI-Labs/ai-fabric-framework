package com.ai.fabric.realapps.mcpops.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("smoke")
@AutoConfigureMockMvc
class McpOperationsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsIsolatedSessionAndProjectsSafeRemoteState() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(get("/api/mcp-ops/sessions/{id}/state", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.selectedService").value("checkout"))
            .andExpect(jsonPath("$.status.revision").value(1))
            .andExpect(jsonPath("$.connection.transport")
                .value("LOCAL_SMOKE_ONLY"))
            .andExpect(jsonPath("$.timeline", hasSize(2)))
            .andExpect(content().string(
                org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString(
                        "_hiddenConnectorTrace"
                    )
                )
            ));

        mockMvc.perform(post(
                "/api/mcp-ops/sessions/{id}/binding-canary",
                sessionId
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passed").value(true))
            .andExpect(jsonPath("$.errorCode")
                .value("MCP_TOOL_NOT_AVAILABLE"))
            .andExpect(jsonPath("$.writeDelta").value(0));
    }

    @Test
    void rejectsInvalidServiceAndCallerSuppliedTrustedArguments()
        throws Exception {
        String sessionId = createSession();

        mockMvc.perform(put(
                "/api/mcp-ops/sessions/{id}/service",
                sessionId
            )
            .contentType("application/json")
            .content("{\"serviceName\":\"shell\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                "/api/mcp-ops/sessions/{id}/chat",
                sessionId
            )
            .header(McpOperationsController.IDEMPOTENCY_HEADER, "turn-1")
            .contentType("application/json")
            .content("""
                {
                  "message": "Check status",
                  "sandboxId": "attacker-selected",
                  "expectedRevision": 999,
                  "tenantId": "other-tenant",
                  "scopes": ["admin"]
                }
                """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                "/api/mcp-ops/sessions/{id}/chat",
                sessionId
            )
            .contentType("application/json")
            .content("{\"message\":\"Check status\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetDeletesSessionStateAndAuditTimeline() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(get("/api/mcp-ops/sessions/{id}/state", sessionId))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/mcp-ops/sessions/{id}", sessionId))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/mcp-ops/sessions/{id}", sessionId))
            .andExpect(status().isBadRequest());
    }

    private String createSession() throws Exception {
        String body = mockMvc.perform(post("/api/mcp-ops/sessions"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionId").value(
                org.hamcrest.Matchers.startsWith("mcp-demo-")
            ))
            .andExpect(jsonPath("$.selectedService").value("checkout"))
            .andExpect(jsonPath("$.availableServices", hasSize(3)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.path("sessionId").asText();
    }
}
