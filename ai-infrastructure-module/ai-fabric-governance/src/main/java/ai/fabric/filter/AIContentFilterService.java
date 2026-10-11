package ai.fabric.filter;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIContentFilterRequest;
import ai.fabric.dto.AIContentFilterResponse;
import ai.fabric.governance.config.AIGovernanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-filter orchestration over typed moderation providers.
 *
 * <p>The framework validates provider identity and applies an explicitly configured recovery chain. It does
 * not infer moderation categories from natural-language keywords. Provider exhaustion fails closed.</p>
 */
@Slf4j
public class AIContentFilterService {

    private static final String PROVIDER_ERROR = "MODERATION_PROVIDER_ERROR";
    private static final String EMPTY_RESULT = "MODERATION_PROVIDER_EMPTY_RESULT";

    private final Map<String, ContentModerationProvider> providers;
    private final String primaryProviderId;
    private final List<String> recoveryProviderIds;

    public AIContentFilterService(
        List<ContentModerationProvider> providers,
        AIGovernanceProperties.ContentFilterProperties properties
    ) {
        Map<String, ContentModerationProvider> indexed = new LinkedHashMap<>();
        if (providers != null) {
            for (ContentModerationProvider provider : providers) {
                if (provider == null || !StringUtils.hasText(provider.providerId())) {
                    continue;
                }
                String id = normalizeProviderId(provider.providerId());
                if (indexed.putIfAbsent(id, provider) != null) {
                    throw new IllegalStateException("Duplicate ContentModerationProvider id: " + id);
                }
            }
        }
        this.providers = Collections.unmodifiableMap(indexed);
        this.primaryProviderId = resolvePrimaryProviderId(properties, indexed);
        this.recoveryProviderIds = resolveRecoveryProviderIds(properties, primaryProviderId, indexed);
    }

    public AIContentFilterResponse filterContent(AIContentFilterRequest request) {
        Objects.requireNonNull(request, "content filter request must not be null");
        long started = System.nanoTime();
        List<Map<String, Object>> attempts = new ArrayList<>();

        for (String providerId : providerChain()) {
            ContentModerationProvider provider = providers.get(providerId);
            if (provider == null) {
                attempts.add(attempt(providerId, false, "PROVIDER_NOT_REGISTERED"));
                continue;
            }
            try {
                ContentModerationResult decision = provider.moderate(request);
                if (decision == null) {
                    attempts.add(attempt(providerId, false, EMPTY_RESULT));
                    continue;
                }
                attempts.add(attempt(providerId, true, null));
                return successfulResponse(request, decision, providerId, attempts, started);
            } catch (Exception ex) {
                log.warn(
                    "Content moderation provider {} failed for request {} ({})",
                    providerId,
                    request.getRequestId(),
                    ex.getClass().getSimpleName()
                );
                attempts.add(attempt(providerId, false, PROVIDER_ERROR));
            }
        }

        return AIContentFilterResponse.builder()
            .requestId(request.getRequestId())
            .subjectId(resolveSubjectId(request))
            .violations(List.of(PROVIDER_ERROR))
            .shouldFilter(true)
            .success(false)
            .errorMessage("No configured moderation provider produced a valid decision.")
            .processingTimeMs(elapsedMillis(started))
            .timestamp(LocalDateTime.now())
            .contentAnalysis(diagnostics(null, attempts, false, Map.of()))
            .build();
    }

    private AIContentFilterResponse successfulResponse(
        AIContentFilterRequest request,
        ContentModerationResult decision,
        String effectiveProvider,
        List<Map<String, Object>> attempts,
        long started
    ) {
        return AIContentFilterResponse.builder()
            .requestId(request.getRequestId())
            .subjectId(resolveSubjectId(request))
            .violations(decision.violations())
            .shouldFilter(decision.shouldFilter())
            .sanitizedContent(decision.sanitizedContent())
            .contentScore(decision.contentScore())
            .recommendations(decision.recommendations())
            .processingTimeMs(elapsedMillis(started))
            .timestamp(LocalDateTime.now())
            .success(true)
            .contentAnalysis(diagnostics(effectiveProvider, attempts, true, decision.metadata()))
            .build();
    }

    private Map<String, Object> diagnostics(
        String effectiveProvider,
        List<Map<String, Object>> attempts,
        boolean success,
        Map<String, Object> providerMetadata
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("decision", "CONTENT_MODERATION");
        diagnostics.put("primaryProvider", primaryProviderId);
        diagnostics.put("configuredRecoveryProviders", recoveryProviderIds);
        diagnostics.put("attempts", List.copyOf(attempts));
        diagnostics.put("recoveryAttempted", attempts.size() > 1);
        diagnostics.put("success", success);
        if (StringUtils.hasText(effectiveProvider)) {
            diagnostics.put("effectiveProvider", effectiveProvider);
        }
        if (providerMetadata != null && !providerMetadata.isEmpty()) {
            diagnostics.put("providerMetadata", Map.copyOf(providerMetadata));
        }
        return Collections.unmodifiableMap(diagnostics);
    }

    private Map<String, Object> attempt(String providerId, boolean success, String reason) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("provider", providerId);
        attempt.put("success", success);
        if (StringUtils.hasText(reason)) {
            attempt.put("reason", reason);
        }
        return Collections.unmodifiableMap(attempt);
    }

    private List<String> providerChain() {
        List<String> chain = new ArrayList<>(1 + recoveryProviderIds.size());
        chain.add(primaryProviderId);
        chain.addAll(recoveryProviderIds);
        return chain;
    }

    private static String resolvePrimaryProviderId(
        AIGovernanceProperties.ContentFilterProperties properties,
        Map<String, ContentModerationProvider> providers
    ) {
        String configured = properties != null ? properties.getPrimaryProvider() : null;
        if (StringUtils.hasText(configured)) {
            String normalized = normalizeProviderId(configured);
            if (!providers.containsKey(normalized)) {
                throw new IllegalStateException("Configured primary moderation provider is not registered: " + normalized);
            }
            return normalized;
        }
        if (providers.size() == 1) {
            return providers.keySet().iterator().next();
        }
        throw new IllegalStateException(
            "ai.governance.content-filter.primary-provider is required when zero or multiple moderation providers exist"
        );
    }

    private static List<String> resolveRecoveryProviderIds(
        AIGovernanceProperties.ContentFilterProperties properties,
        String primaryProviderId,
        Map<String, ContentModerationProvider> providers
    ) {
        List<String> configured = properties != null && properties.getRecoveryProviders() != null
            ? properties.getRecoveryProviders()
            : List.of();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String value : configured) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = normalizeProviderId(value);
            if (normalized.equals(primaryProviderId)) {
                continue;
            }
            if (!providers.containsKey(normalized)) {
                throw new IllegalStateException("Configured moderation recovery provider is not registered: " + normalized);
            }
            resolved.add(normalized);
        }
        return List.copyOf(resolved);
    }

    private static String normalizeProviderId(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String resolveSubjectId(AIContentFilterRequest request) {
        if (request == null || request.getAuthContext() == null) {
            return null;
        }
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (StringUtils.hasText(authContext.getSubjectId())) {
            return authContext.getSubjectId();
        }
        return StringUtils.hasText(authContext.getSessionId()) ? authContext.getSessionId() : null;
    }

    private long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
