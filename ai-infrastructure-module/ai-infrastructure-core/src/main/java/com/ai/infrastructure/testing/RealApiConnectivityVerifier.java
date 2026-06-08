package com.ai.infrastructure.testing;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AIAccessSubjectContexts;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.exception.AIServiceException;
import com.ai.infrastructure.provider.AIProviderManager;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal, provider-agnostic connectivity verification for Real API test suites.
 *
 * <p>Goal: fail fast with a clear error when credentials are invalid (401/403) or the provider
 * is unreachable, instead of discovering it deep into a long-running test suite.</p>
 */
public final class RealApiConnectivityVerifier {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/testing/connectivity-check/v1/system.md";
    private static final String USER_PROMPT_RESOURCE = "prompts/testing/connectivity-check/v1/user.md";

    private RealApiConnectivityVerifier() {
    }

    public static void verifyLlmOrThrow(AIProviderManager providerManager, AIProviderConfig providerConfig) {
        Objects.requireNonNull(providerManager, "providerManager");
        Objects.requireNonNull(providerConfig, "providerConfig");

        VerificationResult result = verifyLlm(providerManager, providerConfig);
        if (result.ok()) {
            return;
        }
        throw new IllegalStateException(result.summary(), result.error());
    }

    public static VerificationResult verifyLlm(AIProviderManager providerManager, AIProviderConfig providerConfig) {
        String llmProvider = normalize(providerConfig.getLlmProvider());
        AIProviderConfig.GenerationDefaults defaults = providerConfig.resolveLlmDefaults();

        if (defaults == null || defaults.model() == null || defaults.model().isBlank()) {
            ClassifiedFailure failure = new ClassifiedFailure(
                FailureType.CONFIG_ERROR,
                null,
                "Missing model for LLM provider '" + llmProvider + "'. Check provider model env vars."
            );
            return VerificationResult.failed(llmProvider, failure, new IllegalStateException(failure.responseSnippet()));
        }

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("connectivity-" + UUID.randomUUID())
            .entityType("connectivity_check")
            .generationType("connectivity_check")
            .authContext(AIAccessSubjectContexts.system("connectivity-check"))
            .systemPrompt(loadPromptOrThrow(SYSTEM_PROMPT_RESOURCE))
            .prompt(loadPromptOrThrow(USER_PROMPT_RESOURCE))
            .model(defaults.model())
            .temperature(0.0)
            .maxTokens(5)
            .build();

        try {
            AIGenerationResponse response = providerManager.generateContent(request);
            String content = response != null ? response.getContent() : null;
            return VerificationResult.ok(llmProvider, content);
        } catch (AIServiceException ex) {
            ClassifiedFailure failure = classify(ex);
            return VerificationResult.failed(llmProvider, failure, ex);
        } catch (RuntimeException ex) {
            ClassifiedFailure failure = classify(ex);
            return VerificationResult.failed(llmProvider, failure, ex);
        }
    }

    public enum FailureType {
        AUTH_ERROR,
        RATE_LIMITED,
        NETWORK_ERROR,
        PROVIDER_ERROR,
        CONFIG_ERROR,
        UNKNOWN
    }

    public record VerificationResult(
        boolean ok,
        String provider,
        FailureType failureType,
        Integer httpStatus,
        String responseSnippet,
        String summary,
        Throwable error
    ) {
        static VerificationResult ok(String provider, String content) {
            String snippet = content == null ? null : content.substring(0, Math.min(120, content.length()));
            return new VerificationResult(
                true,
                provider,
                null,
                null,
                snippet,
                "LLM connectivity check passed for provider=" + provider,
                null
            );
        }

        static VerificationResult failed(String provider, ClassifiedFailure failure, Throwable error) {
            String statusPart = failure.httpStatus() == null ? "" : (" (HTTP " + failure.httpStatus() + ")");
            String hint = switch (failure.type()) {
                case AUTH_ERROR -> "Check API key + provider enabled flags (e.g. OPENAI_API_KEY, OPENAI_ENABLED).";
                case RATE_LIMITED -> "Provider rate-limited the request. Reduce concurrency or wait, then retry.";
                case NETWORK_ERROR -> "Network/timeout talking to provider. Check connectivity or provider uptime.";
                case PROVIDER_ERROR -> "Provider returned a server error. Retry later or switch provider.";
                case CONFIG_ERROR -> "Provider configuration appears invalid. Check base URL / model / deployment names.";
                case UNKNOWN -> "See exception for details.";
            };
            String message = "LLM connectivity check failed for provider=" + provider
                + " type=" + failure.type()
                + statusPart
                + ". " + hint;
            return new VerificationResult(false, provider, failure.type(), failure.httpStatus(), failure.responseSnippet(), message, error);
        }
    }

    private record ClassifiedFailure(FailureType type, Integer httpStatus, String responseSnippet) {
    }

    private static ClassifiedFailure classify(Throwable error) {
        HttpStatusCodeException statusEx = findCause(error, HttpStatusCodeException.class);
        if (statusEx != null) {
            HttpStatusCode code = statusEx.getStatusCode();
            Integer status = code != null ? code.value() : null;
            String body = statusEx.getResponseBodyAsString();
            String snippet = body == null ? null : body.substring(0, Math.min(200, body.length()));

            if (status != null) {
                if (status == 401 || status == 403) {
                    return new ClassifiedFailure(FailureType.AUTH_ERROR, status, snippet);
                }
                if (status == 429) {
                    return new ClassifiedFailure(FailureType.RATE_LIMITED, status, snippet);
                }
                if (status == 400) {
                    return new ClassifiedFailure(FailureType.CONFIG_ERROR, status, snippet);
                }
                if (status >= 500) {
                    return new ClassifiedFailure(FailureType.PROVIDER_ERROR, status, snippet);
                }
            }
            return new ClassifiedFailure(FailureType.UNKNOWN, status, snippet);
        }

        ResourceAccessException resourceAccess = findCause(error, ResourceAccessException.class);
        if (resourceAccess != null) {
            return new ClassifiedFailure(FailureType.NETWORK_ERROR, null, safeMessage(resourceAccess));
        }

        // AICoreService wraps "No AI providers available" / other setup errors
        String message = safeMessage(error);
        if (message != null && message.toLowerCase(Locale.ROOT).contains("no ai providers available")) {
            return new ClassifiedFailure(FailureType.CONFIG_ERROR, null, message);
        }

        return new ClassifiedFailure(FailureType.UNKNOWN, null, message);
    }

    private static String safeMessage(Throwable t) {
        String msg = t == null ? null : t.getMessage();
        if (msg == null) {
            return null;
        }
        return msg.substring(0, Math.min(200, msg.length()));
    }

    private static <T> T findCause(Throwable root, Class<T> type) {
        Throwable current = root;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static String loadPromptOrThrow(String resourcePath) {
        try (InputStream stream = RealApiConnectivityVerifier.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing prompt resource on classpath: " + resourcePath);
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (content.isBlank()) {
                throw new IllegalStateException("Prompt resource is blank: " + resourcePath);
            }
            return content;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load prompt resource: " + resourcePath, ex);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
