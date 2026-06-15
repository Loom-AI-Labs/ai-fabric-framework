package ai.fabric.relay.service;

import ai.fabric.relay.api.TraceContextDto;
import ai.fabric.relay.api.VerifiedAuthContextDto;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RelayTraceContextSupport {

    private RelayTraceContextSupport() {
    }

    static Map<String, String> forwardHeaders(TraceContextDto trace) {
        Map<String, String> out = new LinkedHashMap<>();
        if (trace == null) {
            return out;
        }
        putIfText(out, "X-AIFABRIC-REQUEST-ID", trace.requestId());
        putIfText(out, "X-AIFABRIC-CONVERSATION-ID", trace.conversationId());

        VerifiedAuthContextDto auth = trace.authContext();
        if (auth != null && StringUtils.hasText(auth.subjectId())) {
            putIfText(out, "X-AIFABRIC-AUTH-SUBJECT-ID", auth.subjectId());
            putIfText(out, "X-AIFABRIC-AUTH-SUBJECT-TYPE", auth.subjectType());
            putIfText(out, "X-AIFABRIC-AUTH-MODE", auth.authMode());
            putIfText(out, "X-AIFABRIC-AUTH-CALLER-TYPE", auth.callerType());
            putIfText(out, "X-AIFABRIC-AUTH-SESSION-ID", auth.sessionId());
            putIfText(out, "X-AIFABRIC-AUTH-DEPLOYMENT-ID", auth.deploymentId());
            putIfText(out, "X-AIFABRIC-AUTH-CUSTOMER-ID", auth.customerId());
            putIfText(out, "X-AIFABRIC-AUTH-TENANT-ID", auth.tenantId());
            putIfText(out, "X-AIFABRIC-AUTH-ISSUER", auth.issuer());
            putIfText(out, "X-AIFABRIC-AUTH-EXPIRES-AT", auth.expiresAt());
            if (auth.grantedScopes() != null && !auth.grantedScopes().isEmpty()) {
                String scopes = auth.grantedScopes().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .reduce((left, right) -> left + "," + right)
                    .orElse(null);
                putIfText(out, "X-AIFABRIC-AUTH-SCOPES", scopes);
            }
            if (auth.audiences() != null && !auth.audiences().isEmpty()) {
                String audiences = auth.audiences().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .reduce((left, right) -> left + "," + right)
                    .orElse(null);
                putIfText(out, "X-AIFABRIC-AUTH-AUDIENCES", audiences);
            }
        }

        return out;
    }

    static String rateLimitKey(TraceContextDto trace) {
        if (trace == null) {
            return "unknown";
        }
        VerifiedAuthContextDto auth = trace.authContext();
        if (auth != null && StringUtils.hasText(auth.subjectId())) {
            return auth.subjectId().trim();
        }
        if (auth != null && StringUtils.hasText(auth.sessionId())) {
            return auth.sessionId().trim();
        }
        return "unknown";
    }

    static String effectiveSessionId(TraceContextDto trace) {
        if (trace == null) {
            return null;
        }
        VerifiedAuthContextDto auth = trace.authContext();
        if (auth != null && StringUtils.hasText(auth.sessionId())) {
            return auth.sessionId().trim();
        }
        if (auth != null && StringUtils.hasText(auth.subjectId())
            && "ANONYMOUS_SESSION".equalsIgnoreCase(auth.subjectType())) {
            return auth.subjectId().trim();
        }
        return null;
    }

    static String effectiveSubjectId(TraceContextDto trace) {
        if (trace == null) {
            return null;
        }
        VerifiedAuthContextDto auth = trace.authContext();
        if (auth != null && StringUtils.hasText(auth.subjectId())) {
            return auth.subjectId().trim();
        }
        return null;
    }

    static String subjectType(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.subjectType()) ? auth.subjectType().trim() : null;
    }

    static String authMode(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.authMode()) ? auth.authMode().trim() : null;
    }

    static String authIssuer(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.issuer()) ? auth.issuer().trim() : null;
    }

    static String callerType(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.callerType()) ? auth.callerType().trim() : null;
    }

    static String deploymentId(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.deploymentId()) ? auth.deploymentId().trim() : null;
    }

    static String customerId(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.customerId()) ? auth.customerId().trim() : null;
    }

    static String tenantId(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && StringUtils.hasText(auth.tenantId()) ? auth.tenantId().trim() : null;
    }

    static List<String> grantedScopes(TraceContextDto trace) {
        VerifiedAuthContextDto auth = trace != null ? trace.authContext() : null;
        return auth != null && auth.grantedScopes() != null ? List.copyOf(auth.grantedScopes()) : List.of();
    }

    private static void putIfText(Map<String, String> out, String key, String value) {
        if (out == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        out.put(key.trim(), value.trim());
    }
}
