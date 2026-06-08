package com.ai.fabric.runtime.auth;

import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class RuntimeRequestAuthResolver {

    private final RuntimeAuthProperties properties;
    private final RuntimePrivateAssertionService runtimePrivateAssertionService;
    private final RuntimePublicTokenService runtimePublicTokenService;

    public RuntimeRequestAuthResolver(RuntimeAuthProperties properties) {
        this(properties, new RuntimePrivateAssertionService(properties), null);
    }

    public RuntimeRequestAuthResolver(RuntimeAuthProperties properties,
                                      RuntimePublicTokenService runtimePublicTokenService) {
        this(properties, new RuntimePrivateAssertionService(properties), runtimePublicTokenService);
    }

    public RuntimeRequestAuthResolver(RuntimeAuthProperties properties,
                                      RuntimePrivateAssertionService runtimePrivateAssertionService,
                                      RuntimePublicTokenService runtimePublicTokenService) {
        this.properties = properties != null ? properties : new RuntimeAuthProperties();
        this.runtimePrivateAssertionService = runtimePrivateAssertionService;
        this.runtimePublicTokenService = runtimePublicTokenService;
    }

    public RuntimeResolvedIdentity resolveVerifiedForChat(HttpServletRequest request) {
        RuntimeResolvedIdentity verified = resolveVerifiedContext(request);
        if (verified != null) {
            return verified;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified runtime auth context is required.");
    }

    public RuntimeResolvedIdentity resolveVerifiedForConversation(HttpServletRequest request) {
        RuntimeResolvedIdentity verified = resolveVerifiedContext(request);
        if (verified != null) {
            return verified;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified runtime auth context is required.");
    }

    public RuntimeResolvedIdentity resolveVerifiedPrivateContext(HttpServletRequest request, String surface) {
        rejectCompetingVerifiedAuthMechanisms(request);
        RuntimeResolvedIdentity verified = resolvePrivateAssertion(request);
        if (verified != null) {
            return verified;
        }
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Private runtime auth context is required"
                + (StringUtils.hasText(surface) ? " for " + surface.trim() : "") + "."
        );
    }

    public void requireTrustedBackendIngress(HttpServletRequest request, String surface) {
        requireTrustedBackendAuthentication(request);
        if (StringUtils.hasText(surface)) {
            log.debug("Runtime trusted-backend ingress authorized for surface={}", surface.trim());
        }
    }

    public void requireScope(RuntimeResolvedIdentity identity, String requiredScope, String surface) {
        if (!StringUtils.hasText(requiredScope) || identity == null || identity.getAuthContext() == null) {
            return;
        }
        List<String> grantedScopes = identity.getAuthContext().getGrantedScopes() != null
            ? identity.getAuthContext().getGrantedScopes()
            : List.of();
        boolean allowed = grantedScopes.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .anyMatch(requiredScope::equals);
        if (!allowed) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Runtime auth context is missing required scope " + requiredScope
                    + (StringUtils.hasText(surface) ? " for " + surface.trim() : "") + "."
            );
        }
    }

    private RuntimeResolvedIdentity resolveVerifiedContext(HttpServletRequest request) {
        rejectCompetingVerifiedAuthMechanisms(request);
        RuntimeResolvedIdentity privateAssertionIdentity = resolvePrivateAssertion(request);
        if (privateAssertionIdentity != null) {
            return privateAssertionIdentity;
        }
        RuntimeResolvedIdentity publicBearerIdentity = resolvePublicBearerToken(request);
        if (publicBearerIdentity != null) {
            return publicBearerIdentity;
        }
        return null;
    }

    private RuntimeResolvedIdentity resolvePrivateAssertion(HttpServletRequest request) {
        if (runtimePrivateAssertionService == null || request == null) {
            return null;
        }
        String authorizationHeader = trimHeader(
            request,
            properties.getIngress().getPrivateAssertions().getAuthorizationHeader()
        );
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }

        requireTrustedBackendAuthentication(request);
        RuntimeAuthContext authContext = runtimePrivateAssertionService.validateAssertionHeader(authorizationHeader);
        validateConfiguredVerifiedIssuer(authContext.getIssuer());
        validateConfiguredVerifiedAudiences(authContext.getAudiences());

        return RuntimeResolvedIdentity.builder()
            .authContext(authContext)
            .warnings(List.of())
            .build();
    }

    private RuntimeResolvedIdentity resolvePublicBearerToken(HttpServletRequest request) {
        if (runtimePublicTokenService == null || request == null || !runtimePublicTokenService.isConfigured()) {
            return null;
        }
        String headerName = properties.getPublicTokens().getAuthorizationHeader();
        String authorizationHeader = trimHeader(request, headerName);
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }
        RuntimeAuthContext authContext = runtimePublicTokenService.validateBearerToken(authorizationHeader);
        return RuntimeResolvedIdentity.builder()
            .authContext(authContext)
            .warnings(List.of())
            .build();
    }

    private void rejectCompetingVerifiedAuthMechanisms(HttpServletRequest request) {
        if (request == null) {
            return;
        }
        String privateAuthorization = trimHeader(
            request,
            properties.getIngress().getPrivateAssertions().getAuthorizationHeader()
        );
        String publicAuthorization = trimHeader(
            request,
            properties.getPublicTokens().getAuthorizationHeader()
        );
        if (StringUtils.hasText(privateAuthorization) && StringUtils.hasText(publicAuthorization)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Multiple runtime auth mechanisms were provided. Use either the private runtime assertion header or the public authorization header, not both."
            );
        }
    }

    private void requireTrustedBackendAuthentication(HttpServletRequest request) {
        RuntimeAuthProperties.TrustedBackend trustedBackend = properties.getIngress().getTrustedBackend();
        String expectedValue = trimToNull(trustedBackend.getApiKeyValue());
        if (!StringUtils.hasText(expectedValue)) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Trusted backend authentication is not configured for private runtime assertions."
            );
        }
        String provided = trimHeader(request, trustedBackend.getApiKeyHeader());
        if (!StringUtils.hasText(provided) || !constantTimeEquals(expectedValue, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Trusted backend authentication failed.");
        }
    }

    private List<String> parseCsvValues(String rawValues) {
        if (!StringUtils.hasText(rawValues)) {
            return List.of();
        }
        return Arrays.stream(rawValues.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private void validateConfiguredVerifiedIssuer(String issuer) {
        List<String> acceptedIssuers = parseConfiguredValues(properties.getIngress().getAcceptedIssuers());
        if (acceptedIssuers.isEmpty()) {
            return;
        }
        String normalizedIssuer = trimToNull(issuer);
        if (!StringUtils.hasText(normalizedIssuer) || !acceptedIssuers.contains(normalizedIssuer)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime verified auth issuer is not allowed.");
        }
    }

    private void validateConfiguredVerifiedAudiences(List<String> audiences) {
        List<String> acceptedAudiences = parseConfiguredValues(properties.getIngress().getAcceptedAudiences());
        if (acceptedAudiences.isEmpty()) {
            return;
        }
        List<String> normalizedAudiences = parseConfiguredValues(audiences);
        if (normalizedAudiences.isEmpty() || normalizedAudiences.stream().noneMatch(acceptedAudiences::contains)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime verified auth audience is not allowed.");
        }
    }

    private List<String> parseConfiguredValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(this::trimToNull)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private String trimHeader(HttpServletRequest request, String headerName) {
        if (request == null || !StringUtils.hasText(headerName)) {
            return null;
        }
        return trimToNull(request.getHeader(headerName.trim()));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
