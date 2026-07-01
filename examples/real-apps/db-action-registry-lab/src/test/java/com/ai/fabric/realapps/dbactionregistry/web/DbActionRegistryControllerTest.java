package com.ai.fabric.realapps.dbactionregistry.web;

import com.ai.fabric.realapps.dbactionregistry.service.DbActionRegistryLabService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DbActionRegistryControllerTest {

    private static final String REGISTRY_KEY = "local-registry-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DbActionRegistryLabService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rawRegistryEndpointRequiresApiKey() throws Exception {
        mockMvc.perform(get("/api/ai/actions/registry"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void rawRegistryEndpointListsAndDeletesApprovedDbActions() throws Exception {
        service.approve(service.proposeTemplate("ticket.lookup").proposalId());

        mockMvc.perform(get("/api/ai/actions/registry")
                .header("X-AIFABRIC-REGISTRY-KEY", REGISTRY_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("ticket.lookup"));

        mockMvc.perform(delete("/api/ai/actions/registry/ticket.lookup")
                .header("X-AIFABRIC-REGISTRY-KEY", REGISTRY_KEY))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/actions/registry")
                .header("X-AIFABRIC-REGISTRY-KEY", REGISTRY_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void demoWorkflowPublishesDiscoversAndExecutesAction() throws Exception {
        String proposalBody = mockMvc.perform(post("/api/demo/db-action-registry/proposals/ticket.lookup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String proposalId = objectMapper.readTree(proposalBody).get("proposalId").asText();

        mockMvc.perform(post("/api/demo/db-action-registry/proposals/{proposalId}/approve", proposalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/demo/db-action-registry/discovery"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dbActions[0].name").value("ticket.lookup"))
            .andExpect(jsonPath("$.runtimeActions[0].name").value("ticket.lookup"));

        mockMvc.perform(post("/api/demo/db-action-registry/execute/ticket.lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "params": {
                        "ticketId": "TCK-1001"
                      },
                      "confirmed": false,
                      "userId": "agent-1"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ticketId").value("TCK-1001"));
    }
}
