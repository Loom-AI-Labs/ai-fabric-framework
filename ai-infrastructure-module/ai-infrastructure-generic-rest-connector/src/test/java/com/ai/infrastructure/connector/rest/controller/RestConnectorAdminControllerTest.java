package com.ai.infrastructure.connector.rest.controller;

import com.ai.infrastructure.connector.rest.config.RestConnectorServiceProperties;
import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestConnectorAdminControllerTest {

    @Test
    void overviewIncludesTraceContextDiagnostics() {
        RestRoutingConfig routingConfig = new RestRoutingConfig();
        routingConfig.getConnector().getInboundAuth().getApiKey().setEnabled(true);
        routingConfig.getConnector().getInboundAuth().getApiKey().setHeader("X-AIFABRIC-API-KEY");

        RestConnectorServiceProperties serviceProperties = new RestConnectorServiceProperties();
        serviceProperties.setRoutingConfigLocation("classpath:/actions-routing.yml");

        RestConnectorAdminController controller = new RestConnectorAdminController(
            routingConfig,
            serviceProperties
        );

        ResponseEntity<?> response = controller.overview();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body.get("traceContext")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> traceContext = (Map<String, Object>) body.get("traceContext");
        assertThat(traceContext).containsEntry("verifiedAuthContextSupported", true);
        assertThat(traceContext).containsEntry("preferredIdentitySource", "authContext");
        assertThat(traceContext).containsEntry("actionPreflightAuthorizationSupported", true);
        assertThat(traceContext.get("forwardedVerifiedAuthHeaders")).isEqualTo(List.of(
            "X-AIFABRIC-AUTH-SUBJECT-ID",
            "X-AIFABRIC-AUTH-SUBJECT-TYPE",
            "X-AIFABRIC-AUTH-MODE",
            "X-AIFABRIC-AUTH-CALLER-TYPE",
            "X-AIFABRIC-AUTH-SESSION-ID",
            "X-AIFABRIC-AUTH-DEPLOYMENT-ID",
            "X-AIFABRIC-AUTH-CUSTOMER-ID",
            "X-AIFABRIC-AUTH-TENANT-ID",
            "X-AIFABRIC-AUTH-ISSUER",
            "X-AIFABRIC-AUTH-EXPIRES-AT",
            "X-AIFABRIC-AUTH-SCOPES",
            "X-AIFABRIC-AUTH-AUDIENCES"
        ));
        assertThat(traceContext.get("templateTraceKeys")).isInstanceOf(List.class);
        assertThat(traceContext.get("guidance").toString()).contains("verified auth context headers");
        assertThat(traceContext.get("guidance").toString()).contains("authz preflight");
    }
}
