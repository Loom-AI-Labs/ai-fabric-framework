package com.ai.fabric.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai.infrastructure.shell.BuiltInShellCatalog;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class RuntimeDeploymentShellConfigService {

    public static final String CONTRACT_VERSION = "SHELL_CONFIG_V1";

    private final RuntimeDeploymentShellConfigProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    private volatile JsonNode root;
    private volatile String contractVersion;
    private volatile List<String> moduleIds;
    private volatile List<String> cardIds;
    private volatile String greetingTitle;
    private volatile String greetingMessage;
    private volatile List<ObjectNode> starterPrompts;

    public RuntimeDeploymentShellConfigService(RuntimeDeploymentShellConfigProperties properties,
                                               ResourceLoader resourceLoader,
                                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.root = defaultConfig(null);
        this.contractVersion = CONTRACT_VERSION;
        this.moduleIds = List.of();
        this.cardIds = List.of();
        this.greetingTitle = null;
        this.greetingMessage = null;
        this.starterPrompts = List.of();
    }

    @PostConstruct
    void load() {
        String location = properties.getConfigFile();
        if (!StringUtils.hasText(location)) {
            root = defaultConfig(null);
            contractVersion = CONTRACT_VERSION;
            moduleIds = List.of();
            cardIds = List.of();
            greetingTitle = null;
            greetingMessage = null;
            starterPrompts = List.of();
            log.info("No deployment shell config file configured.");
            return;
        }

        Resource resource = RuntimeDeploymentResolvedConfigSupport.resolveResource(resourceLoader, location);
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("Deployment shell config not found: " + location);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode loaded = RuntimeDeploymentResolvedConfigSupport.readConfig(location, inputStream, objectMapper);
            ObjectNode sanitized = sanitize(loaded);
            validateContractVersion(sanitized.path("contractVersion").asText(CONTRACT_VERSION), location);
            root = sanitized;
            contractVersion = sanitized.path("contractVersion").asText(CONTRACT_VERSION);
            moduleIds = readStringList(sanitized.path("modules"), "id");
            cardIds = readStringList(sanitized.path("cards"), "id");
            greetingTitle = readTrimmedText(sanitized.path("greeting"), "title");
            greetingMessage = readTrimmedText(sanitized.path("greeting"), "message");
            starterPrompts = readStarterPrompts(sanitized.path("starterPrompts"));
            log.info(
                "Loaded deployment shell config from {} with {} module(s), {} card(s), and {} starter prompt(s).",
                location,
                moduleIds.size(),
                cardIds.size(),
                starterPrompts.size()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load deployment shell config from " + location, ex);
        }
    }

    public JsonNode currentRoot() {
        return root;
    }

    public String currentContractVersion() {
        return contractVersion;
    }

    public List<String> currentModuleIds() {
        return moduleIds;
    }

    public List<String> currentCardIds() {
        return cardIds;
    }

    public int currentModuleCount() {
        return moduleIds.size();
    }

    public int currentCardCount() {
        return cardIds.size();
    }

    public String currentGreetingTitle() {
        return greetingTitle;
    }

    public String currentGreetingMessage() {
        return greetingMessage;
    }

    public List<JsonNode> currentStarterPrompts() {
        return List.copyOf(starterPrompts);
    }

    public int currentStarterPromptCount() {
        return starterPrompts.size();
    }

    private ObjectNode sanitize(JsonNode candidate) {
        ObjectNode sanitized = defaultConfig(candidate);
        JsonNode modules = candidate != null ? candidate.path("modules") : null;
        if (modules instanceof ArrayNode arrayNode) {
            sanitized.set("modules", sanitizeModules(arrayNode));
        }
        JsonNode cards = candidate != null ? candidate.path("cards") : null;
        if (cards instanceof ArrayNode arrayNode) {
            sanitized.set("cards", sanitizeCards(arrayNode));
        }
        JsonNode greeting = candidate != null ? candidate.path("greeting") : null;
        if (greeting != null && greeting.isObject()) {
            sanitized.set("greeting", sanitizeGreeting(greeting));
        }
        JsonNode starterPrompts = candidate != null ? candidate.path("starterPrompts") : null;
        if (starterPrompts instanceof ArrayNode arrayNode) {
            sanitized.set("starterPrompts", sanitizeStarterPrompts(arrayNode));
        }
        return sanitized;
    }

    private ObjectNode defaultConfig(JsonNode candidate) {
        ObjectNode root = objectMapper.createObjectNode();
        String effectiveContractVersion = candidate != null && candidate.path("contractVersion").isTextual()
            ? candidate.path("contractVersion").asText().trim()
            : CONTRACT_VERSION;
        root.put("contractVersion", StringUtils.hasText(effectiveContractVersion) ? effectiveContractVersion : CONTRACT_VERSION);
        root.set("modules", objectMapper.createArrayNode());
        root.set("cards", objectMapper.createArrayNode());
        root.set("starterPrompts", objectMapper.createArrayNode());
        root.set("greeting", objectMapper.createObjectNode());
        return root;
    }

    private void validateContractVersion(String actualContractVersion, String location) {
        String effective = StringUtils.hasText(actualContractVersion) ? actualContractVersion.trim() : CONTRACT_VERSION;
        if (!CONTRACT_VERSION.equals(effective)) {
            throw new IllegalStateException(
                "Unsupported deployment shell config contract version '" + effective
                    + "' in " + location + ". Supported version: " + CONTRACT_VERSION
            );
        }
    }

    private ArrayNode sanitizeModules(ArrayNode candidate) {
        ArrayNode sanitized = objectMapper.createArrayNode();
        for (JsonNode entry : candidate) {
            if (!entry.isObject()) {
                continue;
            }
            String id = entry.path("id").asText("").trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            if (!BuiltInShellCatalog.supportsModuleId(id)) {
                throw new IllegalStateException("Unsupported shell module id: " + id);
            }
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("id", id);
            if (!entry.path("enabled").isMissingNode() && entry.path("enabled").isBoolean()) {
                normalized.put("enabled", entry.path("enabled").asBoolean());
            }
            sanitized.add(normalized);
        }
        return sanitized;
    }

    private ArrayNode sanitizeCards(ArrayNode candidate) {
        ArrayNode sanitized = objectMapper.createArrayNode();
        for (JsonNode entry : candidate) {
            if (!entry.isObject()) {
                continue;
            }
            String id = entry.path("id").asText("").trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            if (!BuiltInShellCatalog.supportsCardId(id)) {
                throw new IllegalStateException("Unsupported shell card id: " + id);
            }
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("id", id);
            if (!entry.path("enabled").isMissingNode() && entry.path("enabled").isBoolean()) {
                normalized.put("enabled", entry.path("enabled").asBoolean());
            }
            sanitized.add(normalized);
        }
        return sanitized;
    }

    private ObjectNode sanitizeGreeting(JsonNode candidate) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        String title = readTrimmedText(candidate, "title");
        if (StringUtils.hasText(title)) {
            sanitized.put("title", title);
        }
        String message = readTrimmedText(candidate, "message");
        if (StringUtils.hasText(message)) {
            sanitized.put("message", message);
        }
        return sanitized;
    }

    private ArrayNode sanitizeStarterPrompts(ArrayNode candidate) {
        ArrayNode sanitized = objectMapper.createArrayNode();
        for (JsonNode entry : candidate) {
            if (!entry.isObject()) {
                continue;
            }
            String label = entry.path("label").asText("").trim();
            String query = entry.path("query").asText("").trim();
            if (!StringUtils.hasText(label) || !StringUtils.hasText(query)) {
                continue;
            }
            ObjectNode normalized = objectMapper.createObjectNode();
            String id = entry.path("id").asText("").trim();
            if (StringUtils.hasText(id)) {
                normalized.put("id", id.toLowerCase(Locale.ROOT));
            }
            normalized.put("label", label);
            normalized.put("query", query);
            String moduleId = entry.path("moduleId").asText("").trim();
            if (StringUtils.hasText(moduleId)) {
                if (!BuiltInShellCatalog.supportsModuleId(moduleId)) {
                    throw new IllegalStateException("Unsupported shell starter prompt module id: " + moduleId);
                }
                normalized.put("moduleId", moduleId);
            }
            String cardId = entry.path("cardId").asText("").trim();
            if (StringUtils.hasText(cardId)) {
                if (!BuiltInShellCatalog.supportsCardId(cardId)) {
                    throw new IllegalStateException("Unsupported shell starter prompt card id: " + cardId);
                }
                normalized.put("cardId", cardId);
            }
            sanitized.add(normalized);
        }
        return sanitized;
    }

    private List<ObjectNode> readStarterPrompts(JsonNode entries) {
        if (!(entries instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<ObjectNode> prompts = new ArrayList<>();
        for (JsonNode entry : arrayNode) {
            if (entry instanceof ObjectNode objectNode) {
                prompts.add(objectNode.deepCopy());
            }
        }
        return List.copyOf(prompts);
    }

    private String readTrimmedText(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String value = node.path(field).asText("").trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private List<String> readStringList(JsonNode entries, String field) {
        if (!(entries instanceof ArrayNode arrayNode)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            if (!node.isObject()) {
                continue;
            }
            String value = node.path(field).asText("").trim();
            if (StringUtils.hasText(value) && !values.contains(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
