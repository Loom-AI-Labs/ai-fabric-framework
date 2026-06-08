package com.ai.infrastructure.provider.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Definition of a provider from the registry.
 * Contains all metadata needed to configure and use a provider.
 */
public class ProviderDefinition {
    private String name;
    private String displayName;
    private ProviderType type;
    private String apiKeyEnvVar;
    private String endpointEnvVar;
    private String deploymentNameEnvVar;
    private String embeddingDeploymentNameEnvVar;
    private boolean required;
    private String defaultModel;
    private String baseUrl;
    private boolean enabled;
    private String description;
    private Integer dimensions;
    private List<String> supportedFeatures;
    private List<String> additionalEnvVars;
    private String notes;

    public ProviderDefinition() {
        this.supportedFeatures = new ArrayList<>();
        this.additionalEnvVars = new ArrayList<>();
    }

    public static ProviderDefinition fromMap(String name, ProviderType type, Map<String, Object> map) {
        ProviderDefinition def = new ProviderDefinition();
        def.name = name;
        def.type = type;
        def.displayName = getString(map, "displayName", name);
        def.apiKeyEnvVar = getString(map, "apiKeyEnvVar", null);
        def.endpointEnvVar = getString(map, "endpointEnvVar", null);
        def.deploymentNameEnvVar = getString(map, "deploymentNameEnvVar", null);
        def.embeddingDeploymentNameEnvVar = getString(map, "embeddingDeploymentNameEnvVar", null);
        def.required = getBoolean(map, "required", false);
        def.defaultModel = getString(map, "defaultModel", null);
        def.baseUrl = getString(map, "baseUrl", null);
        def.enabled = getBoolean(map, "enabled", true);
        def.description = getString(map, "description", "");
        def.dimensions = getInteger(map, "dimensions", null);
        def.notes = getString(map, "notes", null);
        
        // Parse supported features
        @SuppressWarnings("unchecked")
        List<String> features = (List<String>) map.get("supportedFeatures");
        if (features != null) {
            def.supportedFeatures = new ArrayList<>(features);
        }
        
        // Parse additional environment variables
        @SuppressWarnings("unchecked")
        List<String> envVars = (List<String>) map.get("additionalEnvVars");
        if (envVars != null) {
            def.additionalEnvVars = new ArrayList<>(envVars);
        }
        
        return def;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static Integer getInteger(Map<String, Object> map, String key, Integer defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get all required environment variables for this provider
     */
    public List<String> getRequiredEnvVars() {
        List<String> vars = new ArrayList<>();
        if (apiKeyEnvVar != null && !apiKeyEnvVar.isEmpty()) {
            vars.add(apiKeyEnvVar);
        }
        if (endpointEnvVar != null && !endpointEnvVar.isEmpty()) {
            vars.add(endpointEnvVar);
        }
        if (deploymentNameEnvVar != null && !deploymentNameEnvVar.isEmpty()) {
            vars.add(deploymentNameEnvVar);
        }
        if (embeddingDeploymentNameEnvVar != null && !embeddingDeploymentNameEnvVar.isEmpty()) {
            vars.add(embeddingDeploymentNameEnvVar);
        }
        if (additionalEnvVars != null) {
            vars.addAll(additionalEnvVars);
        }
        return vars;
    }

    /**
     * Check if this provider is available (has required environment variables)
     */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        if (!required) {
            return true; // Optional providers are always "available"
        }
        List<String> requiredVars = getRequiredEnvVars();
        if (requiredVars.isEmpty()) {
            return true; // No requirements
        }
        // Check if at least API key is present (for most providers)
        if (apiKeyEnvVar != null && System.getenv(apiKeyEnvVar) == null) {
            return false;
        }
        // For Azure, also check endpoint
        if (endpointEnvVar != null && System.getenv(endpointEnvVar) == null) {
            return false;
        }
        return true;
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ProviderType getType() {
        return type;
    }

    public String getApiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    public String getEndpointEnvVar() {
        return endpointEnvVar;
    }

    public String getDeploymentNameEnvVar() {
        return deploymentNameEnvVar;
    }

    public String getEmbeddingDeploymentNameEnvVar() {
        return embeddingDeploymentNameEnvVar;
    }

    public boolean isRequired() {
        return required;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDescription() {
        return description;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public List<String> getSupportedFeatures() {
        return Collections.unmodifiableList(supportedFeatures);
    }

    public List<String> getAdditionalEnvVars() {
        return Collections.unmodifiableList(additionalEnvVars);
    }

    public String getNotes() {
        return notes;
    }
}
