package com.ai.fabric.runtime;

import com.ai.fabric.runtime.admin.RuntimeConnectorAdminProxyService;
import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimePrivateAssertionService;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.ai.fabric.runtime.web.admin.RuntimeConnectorAdminProxyController;
import com.ai.fabric.runtime.web.admin.RuntimeAdminScopeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeConnectorAdminProxyControllerTest {

    @Test
    void overviewRequiresAdminApiKey() {
        RuntimeConnectorAdminProxyService proxyService = mock(RuntimeConnectorAdminProxyService.class);
        RuntimeAuthProperties authProperties = authProperties();
        RuntimeConnectorAdminProxyController controller = new RuntimeConnectorAdminProxyController(
            proxyService,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.overview(new MockHttpServletRequest()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("Private runtime auth context is required");
        verifyNoInteractions(proxyService);
    }

    @Test
    void overviewReturnsConnectorProxyResponseForAuthorizedAdmin() {
        RuntimeConnectorAdminProxyService proxyService = mock(RuntimeConnectorAdminProxyService.class);
        when(proxyService.forwardGet("/api/admin/overview"))
            .thenReturn(proxyResponse(
                200,
                "{\"success\":true,\"surface\":\"connector-overview\"}",
                "application/json"
            ));

        RuntimeAuthProperties authProperties = authProperties();
        RuntimeConnectorAdminProxyController controller = new RuntimeConnectorAdminProxyController(
            proxyService,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );
        ReflectionTestUtils.setField(controller, "connectorBaseUrl", "https://connector.internal");

        ResponseEntity<String> response = controller.overview(authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_CONNECTOR_READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("connector-overview");
        assertThat(response.getBody()).contains("\"runtimeProxy\":{\"enabled\":true,\"baseUrl\":\"https://connector.internal\"}");
    }

    @Test
    void configReturnsConnectorOverviewForAuthorizedAdmin() {
        RuntimeConnectorAdminProxyService proxyService = mock(RuntimeConnectorAdminProxyService.class);
        when(proxyService.forwardGet("/api/admin/overview"))
            .thenReturn(proxyResponse(
                200,
                "{\"success\":true,\"surface\":\"connector-config\"}",
                "application/json"
            ));

        RuntimeAuthProperties authProperties = authProperties();
        RuntimeConnectorAdminProxyController controller = new RuntimeConnectorAdminProxyController(
            proxyService,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );
        ReflectionTestUtils.setField(controller, "connectorBaseUrl", "https://connector.internal");

        ResponseEntity<String> response = controller.config(authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_CONNECTOR_READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("connector-config");
        assertThat(response.getBody()).contains("\"runtimeProxy\":{\"enabled\":true,\"baseUrl\":\"https://connector.internal\"}");
        verify(proxyService).forwardGet("/api/admin/overview");
    }

    @Test
    void logsReturnsConnectorLogfileForAuthorizedAdmin() {
        RuntimeConnectorAdminProxyService proxyService = mock(RuntimeConnectorAdminProxyService.class);
        when(proxyService.forwardGet("/actuator/logfile"))
            .thenReturn(proxyResponse(
                200,
                "connector log line",
                "text/plain"
            ));

        RuntimeAuthProperties authProperties = authProperties();
        RuntimeConnectorAdminProxyController controller = new RuntimeConnectorAdminProxyController(
            proxyService,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );

        ResponseEntity<String> response = controller.logs(authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_CONNECTOR_READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("connector log line");
        verify(proxyService).forwardGet("/actuator/logfile");
    }

    @Test
    void actionReturnsConnectorActionReadForAuthorizedAdmin() {
        RuntimeConnectorAdminProxyService proxyService = mock(RuntimeConnectorAdminProxyService.class);
        when(proxyService.forwardGet("/api/admin/actions/example"))
            .thenReturn(proxyResponse(
                200,
                "{\"success\":true,\"surface\":\"connector-action\"}",
                "application/json"
            ));

        RuntimeAuthProperties authProperties = authProperties();
        RuntimeConnectorAdminProxyController controller = new RuntimeConnectorAdminProxyController(
            proxyService,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );

        ResponseEntity<String> response = controller.action("example", authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_CONNECTOR_READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("connector-action");
        verify(proxyService).forwardGet("/api/admin/actions/example");
    }

    @Test
    void actionRejectsBlankActionIds() {
        RuntimeConnectorAdminProxyService proxyService = mock(RuntimeConnectorAdminProxyService.class);
        RuntimeAuthProperties authProperties = authProperties();
        RuntimeConnectorAdminProxyController controller = new RuntimeConnectorAdminProxyController(
            proxyService,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );

        ResponseEntity<String> response = controller.action("   ", authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_CONNECTOR_READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("actionId is required");
        verifyNoInteractions(proxyService);
    }

    private RuntimeConnectorAdminProxyService.ProxyResponse proxyResponse(int status, String body, String contentType) {
        try {
            Class<?> proxyResponseClass = Class.forName("com.ai.fabric.runtime.admin.RuntimeConnectorAdminProxyService$ProxyResponse");
            Object instance = proxyResponseClass.getDeclaredConstructor(int.class, String.class, String.class)
                .newInstance(status, body, contentType);
            return (RuntimeConnectorAdminProxyService.ProxyResponse) instance;
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private RuntimeAuthProperties authProperties() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().getTrustedBackend().setApiKeyValue("runtime-secret");
        properties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-secret");
        return properties;
    }

    private MockHttpServletRequest authorizedRequest(RuntimeAuthProperties authProperties, String scope) {
        RuntimePrivateAssertionService privateAssertionService = new RuntimePrivateAssertionService(authProperties);
        RuntimeAuthContext authContext = RuntimeAuthContext.builder()
            .subjectId("platform-admin")
            .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
            .authMode(RuntimeAuthMode.PLATFORM_PROXY_SESSION)
            .callerType(RuntimeAuthCallerType.PLATFORM_PROXY)
            .deploymentId("dep-runtime")
            .sessionId("connector-admin-test")
            .issuer("platform-runtime:SESSION")
            .audiences(List.of("dep-runtime"))
            .expiresAt(Instant.now().plusSeconds(300))
            .grantedScopes(List.of(scope))
            .build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-AIFABRIC-RUNTIME-API-KEY", "runtime-secret");
        request.addHeader(
            privateAssertionService.authorizationHeaderName(),
            privateAssertionService.tokenScheme() + " " + privateAssertionService.issueAssertion(authContext)
        );
        return request;
    }
}
