package com.ai.infrastructure.provider.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for loading and querying provider registry.
 * Provides access to provider definitions from providers-registry.yml
 */
public class ProviderRegistryService {
    
    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryService.class);
    private static final String REGISTRY_FILE = "providers-registry.yml";
    
    private static ProviderRegistryService instance;
    private final Map<String, ProviderDefinition> llmProviders = new HashMap<>();
    private final Map<String, ProviderDefinition> embeddingProviders = new HashMap<>();
    private boolean loaded = false;

    private ProviderRegistryService() {
        loadRegistry();
    }

    /**
     * Get singleton instance
     */
    public static synchronized ProviderRegistryService getInstance() {
        if (instance == null) {
            instance = new ProviderRegistryService();
        }
        return instance;
    }

    /**
     * Load provider registry from YAML file
     */
    private void loadRegistry() {
        if (loaded) {
            return;
        }
        
        try {
            ClassPathResource resource = new ClassPathResource(REGISTRY_FILE);
            if (!resource.exists()) {
                log.warn("Provider registry file {} not found, using empty registry", REGISTRY_FILE);
                loaded = true;
                return;
            }

            Yaml yaml = new Yaml();
            try (InputStream inputStream = resource.getInputStream()) {
                Map<String, Object> data = yaml.load(inputStream);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> providers = (Map<String, Object>) data.get("providers");
                if (providers == null) {
                    log.warn("No 'providers' section found in registry file");
                    loaded = true;
                    return;
                }

                // Load LLM providers
                @SuppressWarnings("unchecked")
                Map<String, Object> llmMap = (Map<String, Object>) providers.get("llm");
                if (llmMap != null) {
                    loadProviders(llmMap, ProviderType.LLM, llmProviders);
                }

                // Load Embedding providers
                @SuppressWarnings("unchecked")
                Map<String, Object> embeddingMap = (Map<String, Object>) providers.get("embedding");
                if (embeddingMap != null) {
                    loadProviders(embeddingMap, ProviderType.EMBEDDING, embeddingProviders);
                }

                log.info("Loaded {} LLM providers and {} embedding providers from registry",
                    llmProviders.size(), embeddingProviders.size());
            }
            loaded = true;
        } catch (Exception e) {
            log.error("Failed to load provider registry from {}", REGISTRY_FILE, e);
            loaded = true; // Mark as loaded to prevent retry loops
        }
    }

    @SuppressWarnings("unchecked")
    private void loadProviders(Map<String, Object> providerMap, ProviderType type, 
                              Map<String, ProviderDefinition> target) {
        for (Map.Entry<String, Object> entry : providerMap.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                Map<String, Object> providerData = (Map<String, Object>) value;
                try {
                    ProviderDefinition def = ProviderDefinition.fromMap(name, type, providerData);
                    target.put(name, def);
                    log.debug("Loaded provider definition: {} ({})", name, type);
                } catch (Exception e) {
                    log.warn("Failed to load provider definition for {}: {}", name, e.getMessage());
                }
            }
        }
    }

    /**
     * Get all LLM provider definitions
     */
    public List<ProviderDefinition> getLLMProviders() {
        return new ArrayList<>(llmProviders.values());
    }

    /**
     * Get all embedding provider definitions
     */
    public List<ProviderDefinition> getEmbeddingProviders() {
        return new ArrayList<>(embeddingProviders.values());
    }

    /**
     * Get available (enabled and configured) LLM providers
     */
    public List<ProviderDefinition> getAvailableLLMProviders() {
        return llmProviders.values().stream()
            .filter(ProviderDefinition::isAvailable)
            .collect(Collectors.toList());
    }

    /**
     * Get available (enabled and configured) embedding providers
     */
    public List<ProviderDefinition> getAvailableEmbeddingProviders() {
        return embeddingProviders.values().stream()
            .filter(ProviderDefinition::isAvailable)
            .collect(Collectors.toList());
    }

    /**
     * Get provider definition by name and type
     */
    public ProviderDefinition getProvider(String name, ProviderType type) {
        Map<String, ProviderDefinition> map = type == ProviderType.LLM ? llmProviders : embeddingProviders;
        return map.get(name);
    }

    /**
     * Check if provider is available
     */
    public boolean isProviderAvailable(String name, ProviderType type) {
        ProviderDefinition def = getProvider(name, type);
        return def != null && def.isAvailable();
    }

    /**
     * Get required environment variables for a provider
     */
    public List<String> getRequiredEnvVars(String name, ProviderType type) {
        ProviderDefinition def = getProvider(name, type);
        if (def == null) {
            return Collections.emptyList();
        }
        return def.getRequiredEnvVars();
    }

    /**
     * Get all provider names for a type
     */
    public List<String> getProviderNames(ProviderType type) {
        Map<String, ProviderDefinition> map = type == ProviderType.LLM ? llmProviders : embeddingProviders;
        return new ArrayList<>(map.keySet());
    }

    /**
     * Get enabled provider names for a type
     */
    public List<String> getEnabledProviderNames(ProviderType type) {
        Map<String, ProviderDefinition> map = type == ProviderType.LLM ? llmProviders : embeddingProviders;
        return map.values().stream()
            .filter(ProviderDefinition::isEnabled)
            .map(ProviderDefinition::getName)
            .collect(Collectors.toList());
    }
}
