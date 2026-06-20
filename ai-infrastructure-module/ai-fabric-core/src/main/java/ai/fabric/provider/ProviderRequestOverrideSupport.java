package ai.fabric.provider;

import ai.fabric.config.AIProviderConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProviderRequestOverrideSupport {

    public static final String PARAM_PROVIDER_CONNECTION_OVERRIDE = "__providerConnectionOverride";
    public static final String KEY_ENDPOINT_PROFILE = "endpointProfile";
    public static final String KEY_API_KEY = "apiKey";
    public static final String KEY_BASE_URL = "baseUrl";
    public static final String KEY_DEPLOYMENT_NAME = "deploymentName";
    public static final String KEY_API_VERSION = "apiVersion";

    private ProviderRequestOverrideSupport() {
    }

    public static Map<String, Object> mergeLlmConnectionOverride(Map<String, Object> parameters,
                                                                 AIProviderConfig.PurposeLlmConnectionConfig config) {
        if (!hasConnectionOverride(config)) {
            return parameters;
        }
        Map<String, Object> merged = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        Map<String, Object> override = new LinkedHashMap<>();
        putIfText(override, KEY_ENDPOINT_PROFILE, config.getEndpointProfile());
        putIfText(override, KEY_API_KEY, config.getApiKey());
        putIfText(override, KEY_BASE_URL, config.getBaseUrl());
        putIfText(override, KEY_DEPLOYMENT_NAME, config.getDeploymentName());
        putIfText(override, KEY_API_VERSION, config.getApiVersion());
        if (!override.isEmpty()) {
            merged.put(PARAM_PROVIDER_CONNECTION_OVERRIDE, override);
        }
        return merged;
    }

    public static Map<String, Object> mergeEmbeddingConnectionOverride(Map<String, Object> parameters,
                                                                       AIProviderConfig config) {
        if (!hasEmbeddingConnectionOverride(config)) {
            return parameters;
        }
        Map<String, Object> merged = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        Map<String, Object> override = new LinkedHashMap<>();
        putIfText(override, KEY_ENDPOINT_PROFILE, config.getEmbeddingEndpointProfile());
        putIfText(override, KEY_API_KEY, config.getEmbeddingApiKey());
        putIfText(override, KEY_BASE_URL, config.getEmbeddingBaseUrl());
        putIfText(override, KEY_DEPLOYMENT_NAME, config.getEmbeddingDeploymentName());
        putIfText(override, KEY_API_VERSION, config.getEmbeddingApiVersion());
        if (!override.isEmpty()) {
            merged.put(PARAM_PROVIDER_CONNECTION_OVERRIDE, override);
        }
        return merged;
    }

    public static LlmConnectionOverride read(Map<String, Object> parameters) {
        if (parameters == null) {
            return LlmConnectionOverride.empty();
        }
        Object raw = parameters.get(PARAM_PROVIDER_CONNECTION_OVERRIDE);
        if (!(raw instanceof Map<?, ?> override)) {
            return LlmConnectionOverride.empty();
        }
        return new LlmConnectionOverride(
            text(override.get(KEY_ENDPOINT_PROFILE)),
            text(override.get(KEY_API_KEY)),
            text(override.get(KEY_BASE_URL)),
            text(override.get(KEY_DEPLOYMENT_NAME)),
            text(override.get(KEY_API_VERSION))
        );
    }

    public static boolean hasConnectionOverride(AIProviderConfig.PurposeLlmConnectionConfig config) {
        return config != null
            && (hasText(config.getEndpointProfile())
            || hasText(config.getApiKey())
            || hasText(config.getBaseUrl())
            || hasText(config.getDeploymentName())
            || hasText(config.getApiVersion()));
    }

    public static boolean hasEmbeddingConnectionOverride(AIProviderConfig config) {
        return config != null
            && (hasText(config.getEmbeddingEndpointProfile())
            || hasText(config.getEmbeddingApiKey())
            || hasText(config.getEmbeddingBaseUrl())
            || hasText(config.getEmbeddingDeploymentName())
            || hasText(config.getEmbeddingApiVersion()));
    }

    public static String cacheDiscriminator(Map<String, Object> parameters) {
        LlmConnectionOverride override = read(parameters);
        if (!override.hasAny()) {
            return "";
        }
        return "endpointProfile=" + safe(override.endpointProfile())
            + "|apiKeyFingerprint=" + fingerprint(override.apiKey())
            + "|baseUrl=" + safe(override.baseUrl())
            + "|deploymentName=" + safe(override.deploymentName())
            + "|apiVersion=" + safe(override.apiVersion());
    }

    private static void putIfText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String safe(String value) {
        return hasText(value) ? value.trim() : "";
    }

    private static String fingerprint(String secret) {
        if (!hasText(secret)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record LlmConnectionOverride(
        String endpointProfile,
        String apiKey,
        String baseUrl,
        String deploymentName,
        String apiVersion
    ) {
        public static LlmConnectionOverride empty() {
            return new LlmConnectionOverride(null, null, null, null, null);
        }

        public boolean hasAny() {
            return hasText(endpointProfile)
                || hasText(apiKey)
                || hasText(baseUrl)
                || hasText(deploymentName)
                || hasText(apiVersion);
        }
    }
}
