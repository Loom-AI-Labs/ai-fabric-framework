package com.ai.infrastructure.connector.rest.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RestRoutingConfigLoader {

    private final ResourceLoader resourceLoader;
    private final Environment environment;
    private final ObjectMapper mapper;
    private final Yaml yaml;

    public RestRoutingConfigLoader(ResourceLoader resourceLoader, Environment environment, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.environment = environment;
        this.mapper = buildMapper(objectMapper);

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    public RestRoutingConfig load(String location) {
        if (!StringUtils.hasText(location)) {
            throw new IllegalStateException("rest-connector.routingConfigLocation is required.");
        }

        Resource resource = resourceLoader.getResource(location.trim());
        if (!resource.exists()) {
            throw new IllegalStateException("Routing config not found: " + location.trim());
        }

        String yamlText;
        try (InputStream in = resource.getInputStream()) {
            yamlText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read routing config YAML from " + location.trim() + ": " + ex.getMessage(), ex);
        }

        String resolved = environment != null ? environment.resolvePlaceholders(yamlText) : yamlText;

        Object root;
        try {
            root = yaml.load(resolved);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid YAML in routing config " + location.trim() + ": " + ex.getMessage(), ex);
        }

        if (root == null) {
            return new RestRoutingConfig();
        }
        if (!(root instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Invalid routing config in " + location.trim() + ": expected a YAML object.");
        }

        Map<String, Object> map = toStringKeyMap(raw);
        try {
            return mapper.convertValue(map, RestRoutingConfig.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse routing config " + location.trim() + ": " + ex.getMessage(), ex);
        }
    }

    private ObjectMapper buildMapper(ObjectMapper base) {
        ObjectMapper out = base != null ? base.copy() : new ObjectMapper();
        out.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        out.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        out.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        return out;
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(entry.getKey().toString(), entry.getValue());
        }
        return out;
    }
}

