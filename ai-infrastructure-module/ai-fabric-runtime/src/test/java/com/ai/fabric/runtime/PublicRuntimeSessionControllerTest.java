package com.ai.fabric.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.EXPIRES;
import static org.springframework.http.HttpHeaders.PRAGMA;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:public-runtime-session-main;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-trusted-backend-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_SIGNING_KEY=public-secret",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_ISSUERS=runtime-public-bootstrap",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_AUDIENCES=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_DEFAULT_AUDIENCE=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ENABLED=true",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ALLOWED_ORIGINS=https://shop.example",
    "ai.shell.deployment.config-file=classpath:test-runtime-shell-config.json",
    "AI_FABRIC_RUNTIME_DEPLOYMENT_ID=dep-public",
    "AI_FABRIC_RUNTIME_CUSTOMER_ID=cus-public",
    "AI_FABRIC_RUNTIME_TENANT_ID=ten-public"
})
@AutoConfigureMockMvc
class PublicRuntimeSessionControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bootstrapIssuesAnonymousTokenAndConversationApisAcceptIt() throws Exception {
        MvcResult bootstrapResult = mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://shop.example")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.authMode").value("PUBLIC_RUNTIME_ANONYMOUS"))
            .andExpect(jsonPath("$.subjectType").value("ANONYMOUS_SESSION"))
            .andExpect(jsonPath("$.deploymentId").value("dep-public"))
            .andExpect(jsonPath("$.customerId").value("cus-public"))
            .andExpect(jsonPath("$.tenantId").value("ten-public"))
            .andExpect(jsonPath("$.grantedScopes[0]").value("chat:query"))
            .andExpect(jsonPath("$.grantedScopes[1]").value("chat:suggestions"))
            .andExpect(jsonPath("$.grantedScopes[2]").value("chat:conversations"))
            .andExpect(jsonPath("$.audiences[0]").value("storefront-chat"))
            .andExpect(jsonPath("$.shellConfig.contractVersion").value("SHELL_CONFIG_V1"))
            .andExpect(jsonPath("$.shellConfig.greetingTitle").value("Commerce Assistant"))
            .andExpect(jsonPath("$.shellConfig.greetingMessage").value("Ask about products, orders, or policy."))
            .andExpect(jsonPath("$.shellConfig.starterPrompts[0].label").value("Browse featured products"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(CACHE_CONTROL, "no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(PRAGMA, "no-cache"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(EXPIRES, "0"))
            .andReturn();

        JsonNode payload = OBJECT_MAPPER.readTree(bootstrapResult.getResponse().getContentAsString());
        String token = payload.path("token").asText();
        String sessionId = payload.path("sessionId").asText();

        assertThat(token).startsWith("rpt1.");
        assertThat(sessionId).startsWith("anon-");

        mockMvc.perform(get("/api/chat/me/conversations")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/me/auth-context")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authMode").value("PUBLIC_RUNTIME_ANONYMOUS"))
            .andExpect(jsonPath("$.subjectType").value("ANONYMOUS_SESSION"))
            .andExpect(jsonPath("$.sessionId").value(sessionId))
            .andExpect(jsonPath("$.deploymentId").value("dep-public"))
            .andExpect(jsonPath("$.customerId").value("cus-public"))
            .andExpect(jsonPath("$.tenantId").value("ten-public"));

        mockMvc.perform(get("/api/chat/me/shell-config")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractVersion").value("SHELL_CONFIG_V1"))
            .andExpect(jsonPath("$.supportedModuleIds[0]").value("search"))
            .andExpect(jsonPath("$.moduleIds[0]").value("product-catalog"))
            .andExpect(jsonPath("$.starterPrompts[1].query").value("Track my latest order"));
    }

    @Test
    void strictConversationApiRejectsRequestsWithoutPublicBearerToken() throws Exception {
        mockMvc.perform(get("/api/chat/me/conversations"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void strictConversationApiRejectsInvalidPublicBearerToken() throws Exception {
        mockMvc.perform(get("/api/chat/me/conversations")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void bootstrapRejectsClientControlledAnonymousIdentityFields() throws Exception {
        MvcResult bootstrapResult = mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://shop.example")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"sessionId":"anon-public-legacy"}
                    """))
            .andExpect(status().isBadRequest())
            .andReturn();

        String errorMessage = bootstrapResult.getResponse().getErrorMessage();
        assertThat(errorMessage).contains("Unexpected request fields are not allowed on public runtime bootstrap");
        assertThat(errorMessage).contains("sessionId");
    }

    @Test
    void strictPublicRuntimeRejectsLegacyQueryRouteEvenWithAnonymousToken() throws Exception {
        MvcResult bootstrapResult = mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://shop.example")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andReturn();

        String token = OBJECT_MAPPER.readTree(bootstrapResult.getResponse().getContentAsString()).path("token").asText();

        mockMvc.perform(post("/api/chat/query")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"query":"Help me"}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void bootstrapRejectsRequestsWithoutAllowedOrigin() throws Exception {
        mockMvc.perform(post("/api/public/chat/session")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void bootstrapRejectsRequestsFromDisallowedOrigin() throws Exception {
        mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://evil.example")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }
}

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:public-runtime-session-disabled;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-trusted-backend-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_SIGNING_KEY=public-secret",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_ISSUERS=runtime-public-bootstrap",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_AUDIENCES=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_DEFAULT_AUDIENCE=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ENABLED=false"
})
@AutoConfigureMockMvc
class PublicRuntimeSessionControllerDisabledBootstrapTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bootstrapEndpointReturnsNotFoundWhenDisabled() throws Exception {
        mockMvc.perform(post("/api/public/chat/session").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }
}

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:public-runtime-session-rate-limit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-trusted-backend-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_SIGNING_KEY=public-secret",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_ISSUERS=runtime-public-bootstrap",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_AUDIENCES=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_DEFAULT_AUDIENCE=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ENABLED=true",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ALLOWED_ORIGINS=https://shop.example",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_MAX_REQUESTS_PER_WINDOW=1",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_RATE_LIMIT_WINDOW_SECONDS=60"
})
@AutoConfigureMockMvc
class PublicRuntimeSessionControllerRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bootstrapEnforcesPerOriginRateLimit() throws Exception {
        mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://shop.example")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://shop.example")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isTooManyRequests());
    }
}

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:public-runtime-session-authenticated;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-trusted-backend-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_SIGNING_KEY=public-secret",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_ISSUERS=commerce-app",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_AUDIENCES=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_DEFAULT_AUDIENCE=storefront-chat",
    "AI_FABRIC_RUNTIME_DEPLOYMENT_ID=dep-public",
    "AI_FABRIC_RUNTIME_CUSTOMER_ID=cus-public",
    "AI_FABRIC_RUNTIME_TENANT_ID=ten-public"
})
@AutoConfigureMockMvc
class PublicRuntimeAuthenticatedChatTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.ai.fabric.runtime.auth.RuntimePublicTokenService runtimePublicTokenService;

    @Test
    void authenticatedPublicTokenCanUseAuthAwareRuntimeEndpoints() throws Exception {
        String token = runtimePublicTokenService.issueAuthenticatedToken(
            "customer-123",
            com.ai.fabric.runtime.auth.RuntimeAuthSubjectType.END_USER,
            "session-public-authenticated",
            "dep-public",
            "cus-public",
            "ten-public",
            java.util.List.of("chat:suggestions", "chat:conversations"),
            "commerce-app",
            java.util.List.of("storefront-chat")
        ).token();

        mockMvc.perform(get("/api/chat/me/auth-context")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subjectId").value("customer-123"))
            .andExpect(jsonPath("$.subjectType").value("END_USER"))
            .andExpect(jsonPath("$.authMode").value("PUBLIC_RUNTIME_AUTHENTICATED"))
            .andExpect(jsonPath("$.sessionId").value("session-public-authenticated"))
            .andExpect(jsonPath("$.deploymentId").value("dep-public"));

        mockMvc.perform(post("/api/chat/me/suggestions")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"content":"Help me with my order","maxSuggestions":3}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.authContext.subjectId").value("customer-123"))
            .andExpect(jsonPath("$.authContext.authMode").value("PUBLIC_RUNTIME_AUTHENTICATED"));

        mockMvc.perform(get("/api/chat/me/conversations")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void authAwareQueryRejectsLegacyIdentityFieldsInRequestBody() throws Exception {
        String token = runtimePublicTokenService.issueAuthenticatedToken(
            "customer-123",
            com.ai.fabric.runtime.auth.RuntimeAuthSubjectType.END_USER,
            "session-public-authenticated",
            "dep-public",
            "cus-public",
            "ten-public",
            java.util.List.of("chat:query"),
            "commerce-app",
            java.util.List.of("storefront-chat")
        ).token();

        mockMvc.perform(post("/api/chat/me/query")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"query":"Help me","userId":"legacy-user","sessionId":"legacy-session"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void authAwareSuggestionsRejectUnexpectedIdentityAliasesInRequestBody() throws Exception {
        String token = runtimePublicTokenService.issueAuthenticatedToken(
            "customer-123",
            com.ai.fabric.runtime.auth.RuntimeAuthSubjectType.END_USER,
            "session-public-authenticated",
            "dep-public",
            "cus-public",
            "ten-public",
            java.util.List.of("chat:suggestions"),
            "commerce-app",
            java.util.List.of("storefront-chat")
        ).token();

        mockMvc.perform(post("/api/chat/me/suggestions")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"content":"Help me with my order","ownerId":"legacy-owner","maxSuggestions":3}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedPublicTokenRejectsLegacyAuthContextRoute() throws Exception {
        String token = runtimePublicTokenService.issueAuthenticatedToken(
            "customer-123",
            com.ai.fabric.runtime.auth.RuntimeAuthSubjectType.END_USER,
            "session-public-authenticated",
            "dep-public",
            "cus-public",
            "ten-public",
            java.util.List.of("chat:query"),
            "commerce-app",
            java.util.List.of("storefront-chat")
        ).token();

        mockMvc.perform(get("/api/chat/auth-context")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }
}

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:public-runtime-session-default-shell;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-trusted-backend-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-public",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_SIGNING_KEY=public-secret",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_ISSUERS=runtime-public-bootstrap",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_ACCEPTED_AUDIENCES=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_TOKEN_DEFAULT_AUDIENCE=storefront-chat",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ENABLED=true",
    "AI_FABRIC_RUNTIME_PUBLIC_BOOTSTRAP_ALLOWED_ORIGINS=https://shop.example",
    "AI_FABRIC_RUNTIME_DEPLOYMENT_ID=dep-public",
    "AI_FABRIC_RUNTIME_CUSTOMER_ID=cus-public",
    "AI_FABRIC_RUNTIME_TENANT_ID=ten-public"
})
@AutoConfigureMockMvc
class PublicRuntimeSessionControllerDefaultShellConfigTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bootstrapReturnsDefaultShellContractWhenNoDeploymentShellConfigIsPresent() throws Exception {
        MvcResult bootstrapResult = mockMvc.perform(post("/api/public/chat/session")
                .header("Origin", "https://shop.example")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.shellConfig.contractVersion").value("SHELL_CONFIG_V1"))
            .andExpect(jsonPath("$.shellConfig.moduleIds").isEmpty())
            .andExpect(jsonPath("$.shellConfig.cardIds").isEmpty())
            .andExpect(jsonPath("$.shellConfig.starterPrompts").isEmpty())
            .andExpect(jsonPath("$.shellConfig.supportedModuleIds[0]").value("search"))
            .andReturn();

        String token = OBJECT_MAPPER.readTree(bootstrapResult.getResponse().getContentAsString()).path("token").asText();

        mockMvc.perform(get("/api/chat/me/shell-config")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractVersion").value("SHELL_CONFIG_V1"))
            .andExpect(jsonPath("$.moduleIds").isEmpty())
            .andExpect(jsonPath("$.cardIds").isEmpty())
            .andExpect(jsonPath("$.starterPrompts").isEmpty())
            .andExpect(jsonPath("$.supportedModuleIds[0]").value("search"));
    }
}
