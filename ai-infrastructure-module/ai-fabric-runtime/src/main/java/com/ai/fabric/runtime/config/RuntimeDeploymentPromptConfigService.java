package com.ai.fabric.runtime.config;

import com.ai.infrastructure.prompt.PromptPreviewOverlaySupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class RuntimeDeploymentPromptConfigService {

    private final RuntimeDeploymentPromptConfigProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    private volatile Map<String, String> promptOverlay = Map.of();
    private volatile Double ragSimilarityThreshold;
    private volatile Integer ragMaxDocumentsUsedForContext;
    private volatile Integer ragMaxContextChars;
    private volatile Boolean smartSuggestionsEnabled;
    private volatile Integer responseGenerationMaxTokensConcise;
    private volatile Integer responseGenerationMaxTokensStandard;
    private volatile Integer responseGenerationMaxTokensDeep;

    public RuntimeDeploymentPromptConfigService(RuntimeDeploymentPromptConfigProperties properties,
                                                ResourceLoader resourceLoader,
                                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.jsonMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @PostConstruct
    void load() {
        String location = properties.getConfigFile();
        if (!StringUtils.hasText(location)) {
            promptOverlay = Map.of();
            ragSimilarityThreshold = null;
            ragMaxDocumentsUsedForContext = null;
            ragMaxContextChars = null;
            smartSuggestionsEnabled = null;
            responseGenerationMaxTokensConcise = null;
            responseGenerationMaxTokensStandard = null;
            responseGenerationMaxTokensDeep = null;
            log.info("No deployment prompt config file configured.");
            return;
        }

        Resource resource = resolveResource(location);
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("Deployment prompt config not found: " + location);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = readConfig(location, inputStream);
            promptOverlay = sanitizePromptOverlay(root);
            ragSimilarityThreshold = sanitizeRagSimilarityThreshold(root);
            ragMaxDocumentsUsedForContext = sanitizePositiveInteger(root, "ragMaxDocumentsUsedForContext");
            ragMaxContextChars = sanitizePositiveInteger(root, "ragMaxContextChars");
            smartSuggestionsEnabled = sanitizeSmartSuggestionsEnabled(root);
            responseGenerationMaxTokensConcise = sanitizePositiveInteger(root, "responseGenerationMaxTokensConcise");
            responseGenerationMaxTokensStandard = sanitizePositiveInteger(root, "responseGenerationMaxTokensStandard");
            responseGenerationMaxTokensDeep = sanitizePositiveInteger(root, "responseGenerationMaxTokensDeep");
            log.info(
                "Loaded deployment prompt config from {} with {} prompt override(s), ragSimilarityThreshold={}, ragMaxDocumentsUsedForContext={}, ragMaxContextChars={}, smartSuggestionsEnabled={}, responseGenerationMaxTokensConcise={}, responseGenerationMaxTokensStandard={}, responseGenerationMaxTokensDeep={}.",
                location,
                promptOverlay.size(),
                ragSimilarityThreshold,
                ragMaxDocumentsUsedForContext,
                ragMaxContextChars,
                smartSuggestionsEnabled,
                responseGenerationMaxTokensConcise,
                responseGenerationMaxTokensStandard,
                responseGenerationMaxTokensDeep
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load deployment prompt config from " + location, ex);
        }
    }

    public Map<String, String> currentPromptOverlay() {
        return promptOverlay;
    }

    public Double currentRagSimilarityThreshold() {
        return ragSimilarityThreshold;
    }

    public Integer currentRagMaxDocumentsUsedForContext() {
        return ragMaxDocumentsUsedForContext;
    }

    public Integer currentRagMaxContextChars() {
        return ragMaxContextChars;
    }

    public Boolean currentSmartSuggestionsEnabled() {
        return smartSuggestionsEnabled;
    }

    public Integer currentResponseGenerationMaxTokensConcise() {
        return responseGenerationMaxTokensConcise;
    }

    public Integer currentResponseGenerationMaxTokensStandard() {
        return responseGenerationMaxTokensStandard;
    }

    public Integer currentResponseGenerationMaxTokensDeep() {
        return responseGenerationMaxTokensDeep;
    }

    private Resource resolveResource(String location) {
        String trimmed = location == null ? "" : location.trim();
        if (!StringUtils.hasText(trimmed)) {
            return null;
        }
        if (ResourceUtils.isUrl(trimmed) || trimmed.startsWith("classpath:") || trimmed.startsWith("file:")) {
            return resourceLoader.getResource(trimmed);
        }
        return resourceLoader.getResource("classpath:" + trimmed);
    }

    private JsonNode readConfig(String location, InputStream inputStream) throws Exception {
        String normalized = location.toLowerCase();
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml")) {
            return yamlMapper.readTree(inputStream);
        }
        return jsonMapper.readTree(inputStream);
    }

    private Map<String, String> sanitizePromptOverlay(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (String key : PromptPreviewOverlaySupport.SUPPORTED_KEYS) {
            JsonNode candidate = root.path(key);
            if (!candidate.isTextual()) {
                continue;
            }
            String value = candidate.asText();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            sanitized.put(key, value.trim());
        }
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    private Double sanitizeRagSimilarityThreshold(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode candidate = root.path("ragSimilarityThreshold");
        if (candidate.isMissingNode() || candidate.isNull()) {
            return null;
        }

        Double parsed = null;
        if (candidate.isNumber()) {
            parsed = candidate.asDouble();
        } else if (candidate.isTextual()) {
            String value = candidate.asText("").trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                parsed = Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (parsed == null || !Double.isFinite(parsed) || parsed < 0.0d || parsed > 1.0d) {
            return null;
        }
        return parsed;
    }

    private Boolean sanitizeSmartSuggestionsEnabled(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode candidate = root.path("smartSuggestionsEnabled");
        if (candidate.isMissingNode() || candidate.isNull()) {
            return null;
        }
        if (candidate.isBoolean()) {
            return candidate.asBoolean();
        }
        if (candidate.isTextual()) {
            String value = candidate.asText("").trim();
            if ("true".equalsIgnoreCase(value)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(value)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private Integer sanitizePositiveInteger(JsonNode root, String field) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode candidate = root.path(field);
        if (candidate.isMissingNode() || candidate.isNull()) {
            return null;
        }

        Integer parsed = null;
        if (candidate.isIntegralNumber()) {
            parsed = candidate.asInt();
        } else if (candidate.isTextual()) {
            String value = candidate.asText("").trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (parsed == null || parsed <= 0) {
            return null;
        }
        return parsed;
    }
}
