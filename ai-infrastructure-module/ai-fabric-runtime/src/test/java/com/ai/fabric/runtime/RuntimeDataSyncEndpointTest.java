package com.ai.fabric.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:runtime-data-sync-endpoint;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public"
})
@AutoConfigureMockMvc
class RuntimeDataSyncEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void vectorSpacesEndpointRequiresTrustedBackendAuth() throws Exception {
        mockMvc.perform(get("/api/ai/data-sync/vector-spaces")
                .header("X-AIFABRIC-RUNTIME-API-KEY", "runtime-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.vectorSpaces[0]").value("product"));
    }
}

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:runtime-data-sync-endpoint-trusted;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public"
})
@AutoConfigureMockMvc
class RuntimeDataSyncTrustedBackendEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void vectorSpacesEndpointRejectsRequestsWithoutTrustedBackendApiKeyInVerifiedMode() throws Exception {
        mockMvc.perform(get("/api/ai/data-sync/vector-spaces"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void vectorSpacesEndpointAcceptsTrustedBackendApiKeyInVerifiedMode() throws Exception {
        mockMvc.perform(get("/api/ai/data-sync/vector-spaces")
                .header("X-AIFABRIC-RUNTIME-API-KEY", "runtime-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.vectorSpaces[0]").value("product"));
    }
}
