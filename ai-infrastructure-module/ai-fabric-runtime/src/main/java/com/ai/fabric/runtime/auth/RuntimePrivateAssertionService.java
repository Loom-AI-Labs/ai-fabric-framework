package com.ai.fabric.runtime.auth;

import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class RuntimePrivateAssertionService {

    private static final String TOKEN_PREFIX = "rpa1";

    private final RuntimeAuthProperties properties;
    private final ObjectMapper objectMapper;

    public RuntimePrivateAssertionService(RuntimeAuthProperties properties) {
        this(properties, new ObjectMapper());
    }

    RuntimePrivateAssertionService(RuntimeAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new RuntimeAuthProperties();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(signingKey());
    }

    public String authorizationHeaderName() {
        return StringUtils.hasText(properties.getIngress().getPrivateAssertions().getAuthorizationHeader())
            ? properties.getIngress().getPrivateAssertions().getAuthorizationHeader().trim()
            : "X-AIFABRIC-RUNTIME-AUTHORIZATION";
    }

    public String tokenScheme() {
        return StringUtils.hasText(properties.getIngress().getPrivateAssertions().getTokenScheme())
            ? properties.getIngress().getPrivateAssertions().getTokenScheme().trim()
            : "Bearer";
    }

    public String issueAssertion(RuntimeAuthContext authContext) {
        if (!isConfigured()) {
            throw new IllegalStateException("Runtime private assertion signing is not configured.");
        }
        validateIssueableContext(authContext);
        return writeToken(authContext);
    }

    public RuntimeAuthContext validateAssertionHeader(String rawAuthorizationHeader) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime private assertions are not configured.");
        }
        String token = extractToken(rawAuthorizationHeader);
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0])) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion.");
        }

        byte[] payloadBytes = decode(parts[1]);
        byte[] actualSignature = decode(parts[2]);
        byte[] expectedSignature = sign(parts[1]);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion signature.");
        }

        try {
            JsonNode payload = objectMapper.readTree(payloadBytes);
            RuntimeAuthContext authContext = RuntimeAuthContext.builder()
                .subjectId(requiredText(payload, "sub"))
                .subjectType(parseEnum(requiredText(payload, "subjectType"), RuntimeAuthSubjectType.class, "subjectType"))
                .authMode(parseEnum(requiredText(payload, "authMode"), RuntimeAuthMode.class, "authMode"))
                .callerType(parseEnum(requiredText(payload, "callerType"), RuntimeAuthCallerType.class, "callerType"))
                .sessionId(trimToNull(textOrNull(payload, "sessionId")))
                .deploymentId(trimToNull(textOrNull(payload, "deploymentId")))
                .customerId(trimToNull(textOrNull(payload, "customerId")))
                .tenantId(trimToNull(textOrNull(payload, "tenantId")))
                .issuer(requiredText(payload, "iss"))
                .audiences(readAudienceClaims(payload.path("aud")))
                .expiresAt(Instant.parse(requiredText(payload, "exp")))
                .grantedScopes(readScopes(payload.path("scopes")))
                .build();
            validateResolvedContext(authContext);
            return authContext;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion payload.");
        }
    }

    private void validateIssueableContext(RuntimeAuthContext authContext) {
        if (authContext == null || !StringUtils.hasText(authContext.getSubjectId())) {
            throw new IllegalArgumentException("Runtime private assertion subjectId is required.");
        }
        if (authContext.getSubjectType() == null) {
            throw new IllegalArgumentException("Runtime private assertion subjectType is required.");
        }
        if (authContext.getAuthMode() == null) {
            throw new IllegalArgumentException("Runtime private assertion authMode is required.");
        }
        if (authContext.getCallerType() == null) {
            throw new IllegalArgumentException("Runtime private assertion callerType is required.");
        }
        if (!StringUtils.hasText(authContext.getIssuer())) {
            throw new IllegalArgumentException("Runtime private assertion issuer is required.");
        }
        if (authContext.getExpiresAt() == null) {
            throw new IllegalArgumentException("Runtime private assertion expiresAt is required.");
        }
        validateResolvedContext(authContext);
    }

    private void validateResolvedContext(RuntimeAuthContext authContext) {
        if (authContext.getExpiresAt() != null && authContext.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime private assertion is expired.");
        }
        if (authContext.getAuthMode() != RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED
            && authContext.getAuthMode() != RuntimeAuthMode.PLATFORM_PROXY_SESSION) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime private assertion auth mode is invalid.");
        }
        if (authContext.getAuthMode() == RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED
            && authContext.getCallerType() != RuntimeAuthCallerType.TRUSTED_BACKEND) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime private assertion caller type is invalid.");
        }
        if (authContext.getAuthMode() == RuntimeAuthMode.PLATFORM_PROXY_SESSION
            && authContext.getCallerType() != RuntimeAuthCallerType.PLATFORM_PROXY) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime private assertion caller type is invalid.");
        }
        if (authContext.getSubjectType() == RuntimeAuthSubjectType.ANONYMOUS_SESSION
            && (!StringUtils.hasText(authContext.getSessionId())
            || !authContext.getSubjectId().equals(authContext.getSessionId()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anonymous runtime private assertion session identity is invalid.");
        }
    }

    private String writeToken(RuntimeAuthContext authContext) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("sub", authContext.getSubjectId());
            payload.put("subjectType", authContext.getSubjectType().name());
            payload.put("authMode", authContext.getAuthMode().name());
            payload.put("callerType", authContext.getCallerType().name());
            if (StringUtils.hasText(authContext.getSessionId())) {
                payload.put("sessionId", authContext.getSessionId());
            }
            if (StringUtils.hasText(authContext.getDeploymentId())) {
                payload.put("deploymentId", authContext.getDeploymentId());
            }
            if (StringUtils.hasText(authContext.getCustomerId())) {
                payload.put("customerId", authContext.getCustomerId());
            }
            if (StringUtils.hasText(authContext.getTenantId())) {
                payload.put("tenantId", authContext.getTenantId());
            }
            payload.put("iss", authContext.getIssuer());
            if (!authContext.getAudiences().isEmpty()) {
                if (authContext.getAudiences().size() == 1) {
                    payload.put("aud", authContext.getAudiences().get(0));
                } else {
                    ArrayNode audiences = payload.putArray("aud");
                    authContext.getAudiences().forEach(audiences::add);
                }
            }
            payload.put("exp", authContext.getExpiresAt().toString());
            ArrayNode scopes = payload.putArray("scopes");
            authContext.getGrantedScopes().forEach(scopes::add);

            String payloadSegment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signatureSegment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(payloadSegment));
            return TOKEN_PREFIX + "." + payloadSegment + "." + signatureSegment;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to issue runtime private assertion.", ex);
        }
    }

    private byte[] sign(String payloadSegment) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign runtime private assertion.", ex);
        }
    }

    private byte[] decode(String encoded) {
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion encoding.");
        }
    }

    private String extractToken(String authorizationHeader) {
        String scheme = tokenScheme();
        if (!StringUtils.hasText(authorizationHeader)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime private assertion is required.");
        }
        String trimmed = authorizationHeader.trim();
        String prefix = scheme + " ";
        if (!trimmed.regionMatches(true, 0, prefix, 0, prefix.length()) || trimmed.length() <= prefix.length()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion header.");
        }
        return trimmed.substring(prefix.length()).trim();
    }

    private List<String> readScopes(JsonNode scopesNode) {
        if (scopesNode == null || !scopesNode.isArray()) {
            return List.of();
        }
        return normalizeValues(java.util.stream.StreamSupport.stream(scopesNode.spliterator(), false)
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .toList());
    }

    private List<String> readAudienceClaims(JsonNode audienceNode) {
        if (audienceNode == null || audienceNode.isMissingNode() || audienceNode.isNull()) {
            return List.of();
        }
        if (audienceNode.isTextual()) {
            return normalizeValues(List.of(audienceNode.asText()));
        }
        if (audienceNode.isArray()) {
            return normalizeValues(java.util.stream.StreamSupport.stream(audienceNode.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .toList());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion field: aud");
    }

    private String requiredText(JsonNode payload, String field) {
        String value = textOrNull(payload, field);
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion field: " + field);
        }
        return value.trim();
    }

    private String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload != null ? payload.path(field) : null;
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> enumType, String field) {
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime private assertion field: " + field);
        }
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String signingKey() {
        String signingKey = properties.getIngress().getPrivateAssertions().getSigningKey();
        return StringUtils.hasText(signingKey) ? signingKey.trim() : null;
    }
}
