package com.ai.fabric.runtime.auth;

import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RuntimePublicTokenService {

    private static final String TOKEN_PREFIX = "rpt1";
    private static final String ORIGIN_HEADER = "Origin";

    private final RuntimeAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Deque<Instant>> bootstrapRateLimitBuckets = new ConcurrentHashMap<>();

    public RuntimePublicTokenService(RuntimeAuthProperties properties) {
        this(properties, new ObjectMapper());
    }

    RuntimePublicTokenService(RuntimeAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new RuntimeAuthProperties();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(signingKey());
    }

    public boolean isBootstrapEnabled() {
        return isConfigured() && properties.getPublicTokens().getBootstrap().isEnabled();
    }

    public void authorizeAnonymousBootstrap(HttpServletRequest request) {
        if (!isBootstrapEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Public runtime bootstrap is not enabled.");
        }

        RuntimeAuthProperties.Bootstrap bootstrap = properties.getPublicTokens().getBootstrap();
        String origin = normalizeOrigin(request != null ? request.getHeader(ORIGIN_HEADER) : null);
        if (!StringUtils.hasText(origin)) {
            if (!bootstrap.isAllowMissingOrigin()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Public runtime bootstrap origin is required.");
            }
        } else if (!isAllowedOrigin(origin, bootstrap.getAllowedOrigins())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Public runtime bootstrap origin is not allowed.");
        }

        enforceBootstrapRateLimit(origin, request != null ? trimToNull(request.getRemoteAddr()) : null);
    }

    public String tokenScheme() {
        return StringUtils.hasText(properties.getPublicTokens().getTokenScheme())
            ? properties.getPublicTokens().getTokenScheme().trim()
            : "Bearer";
    }

    public IssuedPublicRuntimeToken issueAnonymousToken() {
        if (!isBootstrapEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Public runtime bootstrap is not enabled.");
        }
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60, properties.getPublicTokens().getTtlSeconds()));
        String sessionId = "anon-" + UUID.randomUUID();
        RuntimeAuthContext authContext = RuntimeAuthContext.builder()
            .subjectId(sessionId)
            .subjectType(RuntimeAuthSubjectType.ANONYMOUS_SESSION)
            .authMode(RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS)
            .callerType(RuntimeAuthCallerType.PUBLIC_BROWSER)
            .sessionId(sessionId)
            .deploymentId(trimToNull(properties.getPublicTokens().getDefaults().getDeploymentId()))
            .customerId(trimToNull(properties.getPublicTokens().getDefaults().getCustomerId()))
            .tenantId(trimToNull(properties.getPublicTokens().getDefaults().getTenantId()))
            .issuer(trimToNull(properties.getPublicTokens().getIssuer()))
            .audiences(defaultAudiences())
            .expiresAt(expiresAt)
            .grantedScopes(anonymousScopes())
            .build();
        return new IssuedPublicRuntimeToken(writeToken(authContext), authContext);
    }

    public IssuedPublicRuntimeToken issueAuthenticatedToken(String subjectId,
                                                            RuntimeAuthSubjectType subjectType,
                                                            String sessionId,
                                                            String deploymentId,
                                                            String customerId,
                                                            String tenantId,
                                                            List<String> grantedScopes,
                                                            String issuer,
                                                            List<String> audiences) {
        String normalizedSubjectId = trimToNull(subjectId);
        if (!StringUtils.hasText(normalizedSubjectId)) {
            throw new IllegalArgumentException("Authenticated runtime public token subjectId is required.");
        }
        if (subjectType == null || subjectType == RuntimeAuthSubjectType.ANONYMOUS_SESSION) {
            throw new IllegalArgumentException("Authenticated runtime public token subjectType must be non-anonymous.");
        }
        List<String> requestedScopes = normalizeValues(grantedScopes);
        List<String> effectiveScopes = requestedScopes.isEmpty()
            ? authenticatedDefaultScopes()
            : requestedScopes;
        validateRequestedAuthenticatedScopes(effectiveScopes);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60, properties.getPublicTokens().getTtlSeconds()));
        RuntimeAuthContext authContext = RuntimeAuthContext.builder()
            .subjectId(normalizedSubjectId)
            .subjectType(subjectType)
            .authMode(RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED)
            .callerType(RuntimeAuthCallerType.PUBLIC_BROWSER)
            .sessionId(trimToNull(sessionId))
            .deploymentId(trimToNull(deploymentId))
            .customerId(trimToNull(customerId))
            .tenantId(trimToNull(tenantId))
            .issuer(trimToNull(issuer) != null ? trimToNull(issuer) : trimToNull(properties.getPublicTokens().getIssuer()))
            .audiences(normalizeValues(audiences).isEmpty() ? defaultAudiences() : normalizeValues(audiences))
            .expiresAt(expiresAt)
            .grantedScopes(effectiveScopes)
            .build();
        return new IssuedPublicRuntimeToken(writeToken(authContext), authContext);
    }

    public RuntimeAuthContext validateBearerToken(String rawAuthorizationHeader) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Public runtime bearer auth is not configured.");
        }
        String token = extractBearerToken(rawAuthorizationHeader);
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0])) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public token.");
        }

        byte[] payloadBytes = decode(parts[1]);
        byte[] actualSignature = decode(parts[2]);
        byte[] expectedSignature = sign(parts[1]);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public token signature.");
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
                .issuer(trimToNull(textOrNull(payload, "iss")))
                .audiences(readAudienceClaims(payload.path("aud")))
                .expiresAt(Instant.parse(requiredText(payload, "exp")))
                .grantedScopes(readScopes(payload.path("scopes")))
                .build();
            if (authContext.getExpiresAt() != null && authContext.getExpiresAt().isBefore(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime public token is expired.");
            }
            if (authContext.getAuthMode() != RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS
                && authContext.getAuthMode() != RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime public token auth mode is not allowed.");
            }
            if (authContext.getCallerType() != RuntimeAuthCallerType.PUBLIC_BROWSER) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime public token caller type is invalid.");
            }
            if (authContext.getAuthMode() == RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS
                && authContext.getSubjectType() != RuntimeAuthSubjectType.ANONYMOUS_SESSION) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anonymous runtime public token subject type is invalid.");
            }
            if (authContext.getAuthMode() == RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS
                && (!StringUtils.hasText(authContext.getSessionId()) || !authContext.getSessionId().equals(authContext.getSubjectId()))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anonymous runtime public token session identity is invalid.");
            }
            if (authContext.getAuthMode() == RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED
                && authContext.getSubjectType() == RuntimeAuthSubjectType.ANONYMOUS_SESSION) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated runtime public token subject type is invalid.");
            }
            validateConfiguredIssuer(authContext.getIssuer());
            validateConfiguredAudiences(authContext.getAudiences());
            validateGrantedScopes(authContext);
            return authContext;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public token payload.");
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
            if (StringUtils.hasText(authContext.getIssuer())) {
                payload.put("iss", authContext.getIssuer());
            }
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
            throw new IllegalStateException("Failed to issue runtime public token.", ex);
        }
    }

    private byte[] sign(String payloadSegment) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign runtime public token.", ex);
        }
    }

    private byte[] decode(String encoded) {
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public token encoding.");
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        String scheme = tokenScheme();
        if (!StringUtils.hasText(authorizationHeader)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime public bearer token is required.");
        }
        String trimmed = authorizationHeader.trim();
        String prefix = scheme + " ";
        if (!trimmed.regionMatches(true, 0, prefix, 0, prefix.length()) || trimmed.length() <= prefix.length()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public authorization header.");
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
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public token field: aud");
    }

    private String requiredText(JsonNode payload, String field) {
        String value = textOrNull(payload, field);
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing runtime public token field: " + field);
        }
        return value.trim();
    }

    private String textOrNull(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> normalizeValues(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(this::trimToNull)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private List<String> defaultAudiences() {
        String defaultAudience = trimToNull(properties.getPublicTokens().getDefaultAudience());
        if (StringUtils.hasText(defaultAudience)) {
            return List.of(defaultAudience);
        }
        String deploymentAudience = trimToNull(properties.getPublicTokens().getDefaults().getDeploymentId());
        if (StringUtils.hasText(deploymentAudience)) {
            return List.of(deploymentAudience);
        }
        return List.of();
    }

    private List<String> anonymousScopes() {
        List<String> configured = normalizeValues(properties.getPublicTokens().getAnonymousGrantedScopes());
        return configured.isEmpty() ? List.of("chat:query", "chat:suggestions", "chat:conversations") : configured;
    }

    private List<String> authenticatedDefaultScopes() {
        List<String> configured = normalizeValues(properties.getPublicTokens().getAuthenticatedDefaultScopes());
        return configured.isEmpty() ? List.of("chat:query", "chat:suggestions", "chat:conversations") : configured;
    }

    private List<String> authenticatedAllowedScopes() {
        List<String> configured = normalizeValues(properties.getPublicTokens().getAuthenticatedAllowedScopes());
        return configured.isEmpty() ? List.of("chat:query", "chat:suggestions", "chat:conversations") : configured;
    }

    private void validateRequestedAuthenticatedScopes(List<String> grantedScopes) {
        List<String> normalizedRequested = normalizeValues(grantedScopes);
        List<String> allowed = authenticatedAllowedScopes();
        if (normalizedRequested.stream().anyMatch(scope -> !allowed.contains(scope))) {
            throw new IllegalArgumentException("Authenticated runtime public token requested scopes exceed the configured allowlist.");
        }
    }

    private void validateGrantedScopes(RuntimeAuthContext authContext) {
        List<String> normalizedScopes = normalizeValues(authContext.getGrantedScopes());
        if (authContext.getAuthMode() == RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS) {
            List<String> allowed = anonymousScopes();
            if (normalizedScopes.stream().anyMatch(scope -> !allowed.contains(scope))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anonymous runtime public token scopes are not allowed.");
            }
            return;
        }
        if (authContext.getAuthMode() == RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED) {
            List<String> allowed = authenticatedAllowedScopes();
            if (normalizedScopes.stream().anyMatch(scope -> !allowed.contains(scope))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated runtime public token scopes are not allowed.");
            }
        }
    }

    private void validateConfiguredIssuer(String issuer) {
        List<String> acceptedIssuers = normalizeValues(properties.getPublicTokens().getAcceptedIssuers());
        if (acceptedIssuers.isEmpty()) {
            return;
        }
        String normalizedIssuer = trimToNull(issuer);
        if (!StringUtils.hasText(normalizedIssuer) || !acceptedIssuers.contains(normalizedIssuer)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime public token issuer is not allowed.");
        }
    }

    private void validateConfiguredAudiences(List<String> audiences) {
        List<String> acceptedAudiences = normalizeValues(properties.getPublicTokens().getAcceptedAudiences());
        if (acceptedAudiences.isEmpty()) {
            return;
        }
        List<String> normalizedAudiences = normalizeValues(audiences);
        if (normalizedAudiences.isEmpty() || normalizedAudiences.stream().noneMatch(acceptedAudiences::contains)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime public token audience is not allowed.");
        }
    }

    private boolean isAllowedOrigin(String normalizedOrigin, List<String> configuredOrigins) {
        if (!StringUtils.hasText(normalizedOrigin)) {
            return false;
        }
        if (configuredOrigins == null || configuredOrigins.isEmpty()) {
            return false;
        }
        for (String candidate : configuredOrigins) {
            String normalizedCandidate = normalizeOrigin(candidate);
            if (StringUtils.hasText(normalizedCandidate) && normalizedCandidate.equals(normalizedOrigin)) {
                return true;
            }
        }
        return false;
    }

    private void enforceBootstrapRateLimit(String normalizedOrigin, String remoteAddress) {
        RuntimeAuthProperties.Bootstrap bootstrap = properties.getPublicTokens().getBootstrap();
        int maxRequests = Math.max(1, bootstrap.getMaxRequestsPerWindow());
        int windowSeconds = Math.max(1, bootstrap.getRateLimitWindowSeconds());
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(windowSeconds);
        String key = (StringUtils.hasText(normalizedOrigin) ? normalizedOrigin : "missing-origin")
            + "|"
            + (StringUtils.hasText(remoteAddress) ? remoteAddress : "unknown");
        Deque<Instant> bucket = bootstrapRateLimitBuckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(threshold)) {
                bucket.removeFirst();
            }
            if (bucket.size() >= maxRequests) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Public runtime bootstrap rate limit exceeded.");
            }
            bucket.addLast(now);
        }
    }

    private String normalizeOrigin(String rawOrigin) {
        String value = trimToNull(rawOrigin);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!StringUtils.hasText(scheme) || !StringUtils.hasText(host)) {
                return null;
            }
            int port = uri.getPort();
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean defaultPort = ("http".equals(normalizedScheme) && port == 80)
                || ("https".equals(normalizedScheme) && port == 443)
                || port < 0;
            return defaultPort
                ? normalizedScheme + "://" + normalizedHost
                : normalizedScheme + "://" + normalizedHost + ":" + port;
        } catch (Exception ex) {
            return null;
        }
    }

    private String signingKey() {
        return trimToNull(properties.getPublicTokens().getSigningKey());
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> enumType, String fieldName) {
        try {
            return Enum.valueOf(enumType, raw.trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runtime public token field: " + fieldName);
        }
    }

    public record IssuedPublicRuntimeToken(String token, RuntimeAuthContext authContext) {
    }
}
