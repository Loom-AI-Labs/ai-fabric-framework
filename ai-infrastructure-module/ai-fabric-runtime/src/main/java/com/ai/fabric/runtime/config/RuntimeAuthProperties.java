package com.ai.fabric.runtime.config;

import com.ai.fabric.runtime.auth.RuntimeAuthIngressMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "ai.fabric.runtime.auth")
public class RuntimeAuthProperties {

    private Ingress ingress = new Ingress();
    private PublicTokens publicTokens = new PublicTokens();

    @Data
    public static class Ingress {
        private RuntimeAuthIngressMode mode = RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED;
        private boolean rejectConflictingRequestIdentity = true;
        private boolean rejectRequestIdentityWhenVerifiedContextPresent = true;
        private List<String> acceptedIssuers = new ArrayList<>();
        private List<String> acceptedAudiences = new ArrayList<>();
        private TrustedBackend trustedBackend = new TrustedBackend();
        private PrivateAssertions privateAssertions = new PrivateAssertions();
    }

    @Data
    public static class TrustedBackend {
        private String apiKeyHeader = "X-AIFABRIC-RUNTIME-API-KEY";
        private String apiKeyValue;
    }

    @Data
    public static class PrivateAssertions {
        private String authorizationHeader = "X-AIFABRIC-RUNTIME-AUTHORIZATION";
        private String tokenScheme = "Bearer";
        private String signingKey;
    }

    @Data
    public static class PublicTokens {
        private String authorizationHeader = "Authorization";
        private String tokenScheme = "Bearer";
        private String signingKey;
        private String issuer = "runtime-public-bootstrap";
        private List<String> acceptedIssuers = new ArrayList<>();
        private List<String> acceptedAudiences = new ArrayList<>();
        private List<String> anonymousGrantedScopes = new ArrayList<>(List.of("chat:query", "chat:suggestions", "chat:conversations"));
        private List<String> authenticatedDefaultScopes = new ArrayList<>(List.of("chat:query", "chat:suggestions", "chat:conversations"));
        private List<String> authenticatedAllowedScopes = new ArrayList<>(List.of("chat:query", "chat:suggestions", "chat:conversations"));
        private String defaultAudience;
        private long ttlSeconds = 900;
        private Bootstrap bootstrap = new Bootstrap();
        private Defaults defaults = new Defaults();
    }

    @Data
    public static class Bootstrap {
        private boolean enabled = false;
        private boolean allowMissingOrigin = false;
        private List<String> allowedOrigins = new ArrayList<>();
        private int maxRequestsPerWindow = 30;
        private int rateLimitWindowSeconds = 60;
    }

    @Data
    public static class Defaults {
        private String deploymentId;
        private String customerId;
        private String tenantId;
    }
}
