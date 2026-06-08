package com.ai.infrastructure.connector.rest.security;

import com.ai.infrastructure.connector.rest.config.CorsConfiguration;
import com.ai.infrastructure.connector.rest.config.RestConnectorAdminAuthProperties;
import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    @Test
    void adminEndpointsUseAdminApiKeyWhenConfigured() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            inboundConfig(),
            new ObjectMapper(),
            new CorsConfiguration.CorsProperties(List.of(), List.of(), false),
            adminProperties("admin-secret")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/overview");
        request.addHeader("X-ADMIN-API-KEY", "admin-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminEndpointsRejectConnectorKeyWhenAdminAuthIsConfigured() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            inboundConfig(),
            new ObjectMapper(),
            new CorsConfiguration.CorsProperties(List.of(), List.of(), false),
            adminProperties("admin-secret")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/overview");
        request.addHeader("X-AIFABRIC-API-KEY", "connector-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void nonAdminEndpointsStillUseConnectorInboundAuth() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            inboundConfig(),
            new ObjectMapper(),
            new CorsConfiguration.CorsProperties(List.of(), List.of(), false),
            adminProperties("admin-secret")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actions/execute");
        request.addHeader("X-AIFABRIC-API-KEY", "connector-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminEndpointsFallBackToConnectorAuthWhenAdminKeyIsNotConfigured() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            inboundConfig(),
            new ObjectMapper(),
            new CorsConfiguration.CorsProperties(List.of(), List.of(), false),
            adminProperties(null)
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/overview");
        request.addHeader("X-AIFABRIC-API-KEY", "connector-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private RestRoutingConfig inboundConfig() {
        RestRoutingConfig config = new RestRoutingConfig();
        config.getConnector().getInboundAuth().setAllowUnauthenticated(false);
        config.getConnector().getInboundAuth().getApiKey().setEnabled(true);
        config.getConnector().getInboundAuth().getApiKey().setHeader("X-AIFABRIC-API-KEY");
        config.getConnector().getInboundAuth().getApiKey().setValue("connector-secret");
        return config;
    }

    private RestConnectorAdminAuthProperties adminProperties(String apiKey) {
        RestConnectorAdminAuthProperties properties = new RestConnectorAdminAuthProperties();
        properties.setApiKey(apiKey);
        properties.setApiKeyHeader("X-ADMIN-API-KEY");
        return properties;
    }
}
