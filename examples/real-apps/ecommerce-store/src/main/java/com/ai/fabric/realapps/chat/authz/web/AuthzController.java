package com.ai.fabric.realapps.chat.authz.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demo authorization API for AI Fabric Runtime product deployments.
 *
 * <p>In production, customers should replace this with their real authorization service.
 * This demo implementation is intentionally simple.</p>
 *
 * <p>Contract: {@code POST /api/authz/check} -> {@code {granted: boolean, reason: string}}</p>
 */
@RestController
@RequestMapping("/api/authz")
public class AuthzController {

    private static final String POLICY_VERSION = "demo-v2";
    private static final String RESOURCE_RAG_INTENT = "rag:intent";
    private static final String OPERATION_READ = "READ";
    private static final String OPERATION_EXECUTE_ACTION = "EXECUTE_ACTION";
    private static final String SUBJECT_TYPE_ANONYMOUS_SESSION = "ANONYMOUS_SESSION";
    private static final String AUTH_MODE_PUBLIC_RUNTIME_ANONYMOUS = "PUBLIC_RUNTIME_ANONYMOUS";
    private static final Set<String> ANONYMOUS_ALLOWED_ACTION_RESOURCES = Set.of(
        "action:list_products",
        "action:search_products",
        "action:get_product_details",
        "action:view_cart",
        "action:add_to_cart",
        "action:remove_from_cart",
        "action:apply_coupon_to_cart"
    );
    private static final Set<String> ANONYMOUS_ALLOWED_ACTION_IDS = Set.of(
        "list_products",
        "search_products",
        "get_product_details",
        "view_cart",
        "add_to_cart",
        "remove_from_cart",
        "apply_coupon_to_cart"
    );

    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthzCheckResponse> check(@RequestBody(required = false) AuthzCheckRequest request) {
        if (request == null) {
            return ResponseEntity.ok(deny("MISSING_AUTH_CONTEXT"));
        }

        CanonicalSubject subject = resolveSubject(request);
        if (!StringUtils.hasText(subject.subjectId())) {
            return ResponseEntity.ok(deny("MISSING_SUBJECT"));
        }

        if (subject.anonymous()) {
            return ResponseEntity.ok(authorizeAnonymous(request));
        }

        return ResponseEntity.ok(allow("ok"));
    }

    private AuthzCheckResponse authorizeAnonymous(AuthzCheckRequest request) {
        String resourceId = trimToNull(request.resourceId());
        String operationType = trimToNull(request.operationType());
        if (RESOURCE_RAG_INTENT.equals(resourceId) && OPERATION_READ.equalsIgnoreCase(operationType)) {
            return allow("anonymous-chat-read");
        }

        if (OPERATION_EXECUTE_ACTION.equalsIgnoreCase(operationType) && anonymousActionAllowed(request, resourceId)) {
            return allow("anonymous-action-allowed");
        }

        return deny("ANONYMOUS_POLICY_DENY");
    }

    private boolean anonymousActionAllowed(AuthzCheckRequest request, String resourceId) {
        if (StringUtils.hasText(resourceId) && ANONYMOUS_ALLOWED_ACTION_RESOURCES.contains(resourceId.trim())) {
            return true;
        }
        String actionId = actionId(request);
        return StringUtils.hasText(actionId) && ANONYMOUS_ALLOWED_ACTION_IDS.contains(actionId);
    }

    private String actionId(AuthzCheckRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, Object> requestContext = request.requestContext();
        if (requestContext != null) {
            Object raw = requestContext.get("actionId");
            if (raw != null && StringUtils.hasText(raw.toString())) {
                return raw.toString().trim();
            }
        }
        Map<String, Object> metadata = request.metadata();
        if (metadata != null) {
            Object raw = metadata.get("actionId");
            if (raw != null && StringUtils.hasText(raw.toString())) {
                return raw.toString().trim();
            }
        }
        return null;
    }

    private CanonicalSubject resolveSubject(AuthzCheckRequest request) {
        VerifiedAuthContext authContext = request.authContext();
        String subjectId = firstText(
            authContext != null ? authContext.subjectId() : null,
            request.subjectId(),
            request.userId()
        );
        String subjectType = firstText(
            authContext != null ? authContext.subjectType() : null,
            request.subjectType()
        );
        String authMode = firstText(
            authContext != null ? authContext.authMode() : null,
            request.authMode()
        );
        boolean anonymous = SUBJECT_TYPE_ANONYMOUS_SESSION.equalsIgnoreCase(subjectType)
            || AUTH_MODE_PUBLIC_RUNTIME_ANONYMOUS.equalsIgnoreCase(authMode);
        return new CanonicalSubject(subjectId, anonymous);
    }

    private AuthzCheckResponse allow(String reason) {
        return new AuthzCheckResponse(true, reason, POLICY_VERSION);
    }

    private AuthzCheckResponse deny(String reason) {
        return new AuthzCheckResponse(false, reason, POLICY_VERSION);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthzCheckRequest(
        String contractVersion,
        String requestId,
        String userId,
        String subjectId,
        String subjectType,
        String authMode,
        String sessionId,
        List<String> grantedScopes,
        List<String> requestedScopes,
        String resourceId,
        String operationType,
        Map<String, Object> requestContext,
        Map<String, Object> metadata,
        Map<String, Object> userAttributes,
        VerifiedAuthContext authContext
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifiedAuthContext(
        String subjectId,
        String subjectType,
        String authMode,
        String callerType,
        String sessionId,
        String deploymentId,
        String customerId,
        String tenantId,
        String issuer,
        List<String> grantedScopes
    ) { }

    public record AuthzCheckResponse(
        boolean granted,
        String reason,
        String policyVersion
    ) { }

    private record CanonicalSubject(
        String subjectId,
        boolean anonymous
    ) { }
}
