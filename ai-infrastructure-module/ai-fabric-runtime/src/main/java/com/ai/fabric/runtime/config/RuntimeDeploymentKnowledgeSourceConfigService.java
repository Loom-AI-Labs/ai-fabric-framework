package com.ai.fabric.runtime.config;

import com.ai.infrastructure.rag.source.KnowledgeSourceAdapterType;
import com.ai.infrastructure.rag.source.ResolvedKnowledgeSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RuntimeDeploymentKnowledgeSourceConfigService {

    public static final String CONTRACT_VERSION = "KNOWLEDGE_SOURCE_CONFIG_V1";

    private final RuntimeDeploymentKnowledgeSourceConfigProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    private volatile JsonNode root;
    private volatile String contractVersion;
    private volatile List<String> sourceIds;
    private volatile List<String> sourceTypes;
    private volatile List<String> sourceAdapterTypes;
    private volatile List<ResolvedKnowledgeSource> sources;

    public RuntimeDeploymentKnowledgeSourceConfigService(RuntimeDeploymentKnowledgeSourceConfigProperties properties,
                                                         ResourceLoader resourceLoader,
                                                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.root = defaultConfig(null);
        this.contractVersion = CONTRACT_VERSION;
        this.sourceIds = List.of();
        this.sourceTypes = List.of();
        this.sourceAdapterTypes = List.of();
        this.sources = List.of();
    }

    @PostConstruct
    void load() {
        String location = properties.getConfigFile();
        if (!StringUtils.hasText(location)) {
            root = defaultConfig(null);
            contractVersion = CONTRACT_VERSION;
            sourceIds = List.of();
            sourceTypes = List.of();
            sourceAdapterTypes = List.of();
            sources = List.of();
            log.info("No deployment knowledge source config file configured.");
            return;
        }

        Resource resource = RuntimeDeploymentResolvedConfigSupport.resolveResource(resourceLoader, location);
        boolean urlResource = ResourceUtils.isUrl(location.trim());
        if (resource == null || (!urlResource && !resource.exists())) {
            throw new IllegalStateException("Deployment knowledge source config not found: " + location);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode loaded = RuntimeDeploymentResolvedConfigSupport.readConfig(location, inputStream, objectMapper);
            ObjectNode sanitized = sanitize(loaded);
            validateContractVersion(sanitized.path("contractVersion").asText(CONTRACT_VERSION), location);
            root = sanitized;
            contractVersion = sanitized.path("contractVersion").asText(CONTRACT_VERSION);
            sources = readSources(sanitized.path("sources"));
            sourceIds = sources.stream().map(ResolvedKnowledgeSource::getId).distinct().toList();
            sourceTypes = sources.stream().map(ResolvedKnowledgeSource::getType).distinct().toList();
            sourceAdapterTypes = sources.stream().map(ResolvedKnowledgeSource::getAdapterType).distinct().toList();
            log.info(
                "Loaded deployment knowledge source config from {} with {} source(s) and adapter types {}.",
                location,
                sourceIds.size(),
                sourceAdapterTypes
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load deployment knowledge source config from " + location, ex);
        }
    }

    public JsonNode currentRoot() {
        return root;
    }

    public String currentContractVersion() {
        return contractVersion;
    }

    public List<String> currentSourceIds() {
        return sourceIds;
    }

    public List<String> currentSourceTypes() {
        return sourceTypes;
    }

    public List<String> currentSourceAdapterTypes() {
        return sourceAdapterTypes;
    }

    public List<ResolvedKnowledgeSource> currentSources() {
        return sources;
    }

    public int currentSourceCount() {
        return sourceIds.size();
    }

    private ObjectNode sanitize(JsonNode candidate) {
        ObjectNode sanitized = defaultConfig(candidate);
        JsonNode sources = candidate != null ? candidate.path("sources") : null;
        if (sources instanceof ArrayNode arrayNode) {
            sanitized.set("sources", arrayNode.deepCopy());
        }
        return sanitized;
    }

    private ObjectNode defaultConfig(JsonNode candidate) {
        ObjectNode root = objectMapper.createObjectNode();
        String effectiveContractVersion = candidate != null && candidate.path("contractVersion").isTextual()
            ? candidate.path("contractVersion").asText().trim()
            : CONTRACT_VERSION;
        root.put("contractVersion", StringUtils.hasText(effectiveContractVersion) ? effectiveContractVersion : CONTRACT_VERSION);
        root.set("sources", objectMapper.createArrayNode());
        return root;
    }

    private void validateContractVersion(String actualContractVersion, String location) {
        String effective = StringUtils.hasText(actualContractVersion) ? actualContractVersion.trim() : CONTRACT_VERSION;
        if (!CONTRACT_VERSION.equals(effective)) {
            throw new IllegalStateException(
                "Unsupported deployment knowledge source config contract version '" + effective
                    + "' in " + location + ". Supported version: " + CONTRACT_VERSION
            );
        }
    }

    private List<ResolvedKnowledgeSource> readSources(JsonNode sourceEntries) {
        if (!(sourceEntries instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<ResolvedKnowledgeSource> resolved = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            if (!node.isObject()) {
                continue;
            }
            String sourceId = node.path("id").asText("").trim();
            if (!StringUtils.hasText(sourceId)) {
                continue;
            }
            String sourceType = node.path("type").asText("").trim();
            String requestedAdapterType = node.path("adapterType").asText("").trim();
            String adapterType = resolveAdapterTypeValue(requestedAdapterType, sourceType);
            String attributionLabel = node.path("attributionLabel").asText("").trim();
            String entityType = node.path("entityType").asText("").trim();
            Map<String, Object> filters = RuntimeDeploymentResolvedConfigSupport.convertToMap(
                objectMapper,
                node.path("filters")
            );
            List<String> authModes = readStringArray(node.path("authModes"));
            String handleRef = node.path("handleRef").asText("").trim();
            boolean enabled = !node.path("enabled").isBoolean() || node.path("enabled").asBoolean();

            resolved.add(ResolvedKnowledgeSource.builder()
                .id(sourceId)
                .type(StringUtils.hasText(sourceType) ? sourceType : adapterType)
                .adapterType(adapterType)
                .attributionLabel(StringUtils.hasText(attributionLabel) ? attributionLabel : sourceId)
                .entityType(StringUtils.hasText(entityType) ? entityType : null)
                .filters(filters)
                .authModes(authModes)
                .handleRef(StringUtils.hasText(handleRef) ? handleRef : null)
                .enabled(enabled)
                .build());
        }
        return List.copyOf(resolved);
    }

    private String resolveAdapterTypeValue(String requestedAdapterType, String sourceType) {
        KnowledgeSourceAdapterType adapterType = KnowledgeSourceAdapterType.fromWireValue(requestedAdapterType);
        if (adapterType != null) {
            return adapterType.wireValue();
        }
        KnowledgeSourceAdapterType sourceBackedType = KnowledgeSourceAdapterType.fromWireValue(sourceType);
        if (sourceBackedType != null) {
            return sourceBackedType.wireValue();
        }
        if ("shared-vector".equalsIgnoreCase(sourceType) || "shared_vector".equalsIgnoreCase(sourceType)) {
            return KnowledgeSourceAdapterType.SHARED_INDEX.wireValue();
        }
        if ("private-vector".equalsIgnoreCase(sourceType) || "private_vector".equalsIgnoreCase(sourceType)) {
            return KnowledgeSourceAdapterType.DEPLOYMENT_PRIVATE_VECTOR.wireValue();
        }
        if (StringUtils.hasText(requestedAdapterType)) {
            return requestedAdapterType.trim();
        }
        return StringUtils.hasText(sourceType) ? sourceType.trim() : "";
    }

    private List<String> readStringArray(JsonNode value) {
        if (!(value instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            if (!node.isTextual()) {
                continue;
            }
            String candidate = node.asText("").trim();
            if (StringUtils.hasText(candidate) && !resolved.contains(candidate)) {
                resolved.add(candidate);
            }
        }
        return List.copyOf(resolved);
    }
}
