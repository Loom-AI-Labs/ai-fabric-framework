package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.admin.RuntimeConnectorAdminProxyService;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/connector")
@RequiredArgsConstructor
public class RuntimeConnectorAdminProxyController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RuntimeConnectorAdminProxyService proxyService;
    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;
    @Value("${ai.actions.connector.base-url:}")
    private String connectorBaseUrl;

    @GetMapping("/overview")
    public ResponseEntity<String> overview(HttpServletRequest httpRequest) {
        authorize(httpRequest, "/api/admin/connector/overview");
        return toResponse(enrichConnectorAdminOverview(proxyService.forwardGet("/api/admin/overview")));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health(HttpServletRequest httpRequest) {
        authorize(httpRequest, "/api/admin/connector/health");
        return toResponse(proxyService.forwardGet("/actuator/health"));
    }

    @GetMapping("/actions/overview")
    public ResponseEntity<String> actionsOverview(HttpServletRequest httpRequest) {
        authorize(httpRequest, "/api/admin/connector/actions/overview");
        return toResponse(proxyService.forwardGet("/api/admin/actions/overview"));
    }

    @GetMapping("/config")
    public ResponseEntity<String> config(HttpServletRequest httpRequest) {
        authorize(httpRequest, "/api/admin/connector/config");
        return toResponse(enrichConnectorAdminOverview(proxyService.forwardGet("/api/admin/overview")));
    }

    @GetMapping("/logs")
    public ResponseEntity<String> logs(HttpServletRequest httpRequest) {
        authorize(httpRequest, "/api/admin/connector/logs");
        return toResponse(proxyService.forwardGet("/actuator/logfile"));
    }

    @GetMapping("/actions/{actionId}")
    public ResponseEntity<String> action(@PathVariable String actionId, HttpServletRequest httpRequest) {
        authorize(httpRequest, "/api/admin/connector/actions/{actionId}");
        if (!StringUtils.hasText(actionId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"actionId is required\"}");
        }
        String encodedActionId = UriUtils.encodePathSegment(actionId.trim(), StandardCharsets.UTF_8);
        return toResponse(proxyService.forwardGet("/api/admin/actions/" + encodedActionId));
    }

    private ResponseEntity<String> toResponse(RuntimeConnectorAdminProxyService.ProxyResponse response) {
        HttpStatus status = HttpStatus.resolve(response != null ? response.status() : 503);
        if (status == null) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }
        MediaType contentType = MediaType.APPLICATION_JSON;
        if (response != null && StringUtils.hasText(response.contentType())) {
            try {
                contentType = MediaType.parseMediaType(response.contentType());
            } catch (Exception ignored) {
                contentType = MediaType.APPLICATION_JSON;
            }
        }
        String body = response != null ? response.body() : null;
        if (!StringUtils.hasText(body) && status.isError()) {
            body = "{\"success\":false,\"message\":\"Connector admin proxy failed.\"}";
        }
        return ResponseEntity.status(status)
            .contentType(contentType)
            .body(body);
    }

    private RuntimeConnectorAdminProxyService.ProxyResponse enrichConnectorAdminOverview(
        RuntimeConnectorAdminProxyService.ProxyResponse response
    ) {
        if (response == null
            || response.status() < 200
            || response.status() >= 300
            || !StringUtils.hasText(response.body())) {
            return response;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(response.body());
            if (!(parsed instanceof ObjectNode objectNode)) {
                return response;
            }
            ObjectNode runtimeProxy = objectNode.with("runtimeProxy");
            runtimeProxy.put("enabled", StringUtils.hasText(connectorBaseUrl));
            runtimeProxy.put("baseUrl", StringUtils.hasText(connectorBaseUrl) ? connectorBaseUrl.trim() : "");
            return new RuntimeConnectorAdminProxyService.ProxyResponse(
                response.status(),
                OBJECT_MAPPER.writeValueAsString(objectNode),
                response.contentType()
            );
        } catch (Exception ignored) {
            return response;
        }
    }

    private void authorize(HttpServletRequest request, String surface) {
        runtimeRequestAuthResolver.requireScope(
            runtimeRequestAuthResolver.resolveVerifiedPrivateContext(request, surface),
            RuntimeAdminScopeCatalog.RUNTIME_CONNECTOR_READ,
            surface
        );
    }
}
