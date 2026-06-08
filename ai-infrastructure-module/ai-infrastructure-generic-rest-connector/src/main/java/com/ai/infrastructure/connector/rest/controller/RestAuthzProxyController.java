package com.ai.infrastructure.connector.rest.controller;

import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import com.ai.infrastructure.connector.rest.service.RestAuthzProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authz proxy endpoint used by the runtime product deployment.
 *
 * <p>Typical topology: Runtime -> REST connector -> customer authz service.</p>
 */
@RestController
@RequestMapping("/api/authz")
@RequiredArgsConstructor
public class RestAuthzProxyController {

    private final RestRoutingConfig routingConfig;
    private final RestAuthzProxyService proxyService;

    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> check(@RequestBody(required = false) String jsonBody) {
        RestRoutingConfig.Authz authz = routingConfig != null ? routingConfig.getAuthz() : null;
        if (authz == null || !authz.isEnabled()) {
            return deny(HttpStatus.SERVICE_UNAVAILABLE, "AUTHZ_PROXY_DISABLED");
        }

        try {
            RestAuthzProxyService.ProxyResponse upstream = proxyService.forward(jsonBody);
            int status = upstream != null ? upstream.status() : 0;
            String body = upstream != null ? upstream.body() : null;

            // Preserve upstream status when possible; runtime treats non-2xx as deny (fail-closed).
            HttpStatus outStatus = status >= 100 && status <= 599 ? HttpStatus.valueOf(status) : HttpStatus.SERVICE_UNAVAILABLE;
            if (StringUtils.hasText(body)) {
                return ResponseEntity.status(outStatus).contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return ResponseEntity.status(outStatus).contentType(MediaType.APPLICATION_JSON).body("{}");
        } catch (Exception ex) {
            return deny(HttpStatus.SERVICE_UNAVAILABLE, "AUTHZ_UPSTREAM_UNAVAILABLE");
        }
    }

    private ResponseEntity<Map<String, Object>> deny(HttpStatus status, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("granted", false);
        body.put("reason", reason);
        return ResponseEntity.status(status).body(body);
    }
}

