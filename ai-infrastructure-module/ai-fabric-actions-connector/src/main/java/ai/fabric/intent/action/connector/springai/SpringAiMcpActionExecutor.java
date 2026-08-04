package ai.fabric.intent.action.connector.springai;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes AI Fabric MCP actions using Spring AI-managed MCP sync clients.
 */
@Slf4j
public class SpringAiMcpActionExecutor implements McpActionExecutor {

    private static final String ERROR_INVALID_CONFIGURATION = "INVALID_CONFIGURATION";
    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String ERROR_MCP_TOOL_EXECUTION_FAILED = "MCP_TOOL_EXECUTION_FAILED";
    private static final String ERROR_MCP_RESULT_TOO_LARGE = "MCP_RESULT_TOO_LARGE";
    private static final int DEFAULT_MAX_RESULT_CHARACTERS = 32_768;
    private static final int MIN_MAX_RESULT_CHARACTERS = 1_024;
    private static final int MAX_MAX_RESULT_CHARACTERS = 262_144;
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private final Supplier<List<McpSyncClient>> clientsSupplier;
    private final ObjectMapper objectMapper;

    public SpringAiMcpActionExecutor(Supplier<List<McpSyncClient>> clientsSupplier, ObjectMapper objectMapper) {
        this.clientsSupplier = clientsSupplier != null ? clientsSupplier : List::of;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public boolean isAvailable() {
        return !clients().isEmpty();
    }

    @Override
    public ActionResult execute(String actionId,
                                ActionAccessMode accessMode,
                                Map<String, Object> params,
                                ActionContext context,
                                Map<String, Object> actionConfig) {
        McpActionSpec spec;
        try {
            spec = McpActionSpec.from(actionConfig);
        } catch (IllegalArgumentException ex) {
            return failure(ERROR_INVALID_CONFIGURATION, ex.getMessage());
        }
        if (!StringUtils.hasText(spec.toolName())) {
            return failure(ERROR_INVALID_CONFIGURATION, "MCP tool action is missing execution.mcp.toolName.");
        }

        List<McpSyncClient> clients = clients();
        if (clients.isEmpty()) {
            return failure(ERROR_MCP_TOOL_NOT_AVAILABLE, "No Spring AI MCP sync clients are available.");
        }

        Optional<McpSyncClient> client = findClient(clients, spec.serverRef(), spec.toolName());
        if (client.isEmpty()) {
            return failure(ERROR_MCP_TOOL_NOT_AVAILABLE, "MCP tool is not available through Spring AI MCP clients: " + spec.toolName());
        }

        Map<String, Object> arguments;
        try {
            arguments = renderArguments(spec.argumentTemplate(), params, context);
        } catch (Exception ex) {
            return failure(ERROR_INVALID_CONFIGURATION, "Failed to render MCP argument template: " + ex.getMessage());
        }

        try {
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(spec.toolName())
                .arguments(arguments)
                .meta(buildMcpMeta(actionId, accessMode, context))
                .build();
            McpSchema.CallToolResult mcpResult = client.get().callTool(request);
            return toActionResult(spec, mcpResult);
        } catch (IllegalArgumentException ex) {
            return failure(ERROR_INVALID_CONFIGURATION, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Spring AI MCP action '{}' failed calling tool '{}': {}", actionId, spec.toolName(), ex.getMessage());
            return failure(ERROR_SERVICE_UNAVAILABLE, "Spring AI MCP tool execution is unavailable.");
        }
    }

    private List<McpSyncClient> clients() {
        try {
            List<McpSyncClient> clients = clientsSupplier.get();
            if (clients == null || clients.isEmpty()) {
                return List.of();
            }
            return clients.stream().filter(Objects::nonNull).toList();
        } catch (Exception ex) {
            log.debug("Failed to resolve Spring AI MCP clients: {}", ex.getMessage());
            return List.of();
        }
    }

    private Optional<McpSyncClient> findClient(List<McpSyncClient> clients, String serverRef, String toolName) {
        List<McpSyncClient> candidates = clients;
        if (StringUtils.hasText(serverRef)) {
            candidates = clients.stream()
                .filter(client -> matchesServerRef(client, serverRef))
                .toList();
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
        }

        for (McpSyncClient client : candidates) {
            if (clientHasTool(client, toolName)) {
                return Optional.of(client);
            }
        }
        return Optional.empty();
    }

    private boolean matchesServerRef(McpSyncClient client, String serverRef) {
        if (client == null || !StringUtils.hasText(serverRef)) {
            return false;
        }
        try {
            initializeIfRequired(client);
            McpSchema.Implementation serverInfo = client.getServerInfo();
            return matchesText(serverInfo != null ? serverInfo.name() : null, serverRef)
                || matchesText(serverInfo != null ? serverInfo.title() : null, serverRef);
        } catch (Exception ex) {
            log.debug("Failed to inspect Spring AI MCP server info: {}", ex.getMessage());
            return false;
        }
    }

    private boolean clientHasTool(McpSyncClient client, String toolName) {
        if (client == null || !StringUtils.hasText(toolName)) {
            return false;
        }
        try {
            initializeIfRequired(client);
            McpSchema.ListToolsResult toolsResult = client.listTools();
            if (toolsResult == null || toolsResult.tools() == null) {
                return false;
            }
            for (McpSchema.Tool tool : toolsResult.tools()) {
                if (tool != null && matchesText(tool.name(), toolName)) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.debug("Failed to list Spring AI MCP tools: {}", ex.getMessage());
        }
        return false;
    }

    private void initializeIfRequired(McpSyncClient client) {
        if (client != null && !client.isInitialized()) {
            client.initialize();
        }
    }

    private boolean matchesText(String candidate, String expected) {
        return StringUtils.hasText(candidate)
            && StringUtils.hasText(expected)
            && candidate.trim().equalsIgnoreCase(expected.trim());
    }

    private Map<String, Object> renderArguments(Object argumentTemplate,
                                                Map<String, Object> params,
                                                ActionContext context) {
        Map<String, Object> safeParams = params != null ? params : Map.of();
        if (argumentTemplate == null) {
            return new LinkedHashMap<>(safeParams);
        }
        Object rendered = renderTemplateValue(argumentTemplate, safeParams, context);
        if (rendered == null) {
            return Map.of();
        }
        if (rendered instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        throw new IllegalStateException("execution.mcp.argumentTemplate must render to an object.");
    }

    private Object renderTemplateValue(Object value, Map<String, Object> params, ActionContext context) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return renderText(text.toString(), params, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                rendered.put(entry.getKey().toString(), renderTemplateValue(entry.getValue(), params, context));
            }
            return rendered;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> rendered = new ArrayList<>();
            for (Object item : collection) {
                rendered.add(renderTemplateValue(item, params, context));
            }
            return List.copyOf(rendered);
        }
        return value;
    }

    private Object renderText(String text, Map<String, Object> params, ActionContext context) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher exact = TEMPLATE_PLACEHOLDER.matcher(text.trim());
        if (exact.matches()) {
            return resolveTemplateExpression(exact.group(1), params, context);
        }

        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object value = resolveTemplateExpression(matcher.group(1), params, context);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Object resolveTemplateExpression(String expression, Map<String, Object> params, ActionContext context) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        String expr = expression.trim();
        if (expr.startsWith("params.")) {
            return resolvePath(params, expr.substring("params.".length()));
        }
        if (expr.startsWith("context.")) {
            return resolveContextPath(context, expr.substring("context.".length()));
        }
        return resolvePath(params, expr);
    }

    private Object resolveContextPath(ActionContext context, String path) {
        if (context == null || !StringUtils.hasText(path)) {
            return null;
        }
        return switch (path.trim()) {
            case "requestId" -> context.requestId();
            case "conversationId" -> context.conversationId();
            case "userId" -> context.userId();
            case "sessionId" -> context.sessionId();
            default -> {
                if (path.startsWith("metadata.")) {
                    yield resolvePath(context.metadata(), path.substring("metadata.".length()));
                }
                yield null;
            }
        };
    }

    private Object resolvePath(Object root, String path) {
        if (root == null || !StringUtils.hasText(path)) {
            return root;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!StringUtils.hasText(segment)) {
                return null;
            }
            current = resolveSegment(current, segment.trim());
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object resolveSegment(Object current, String segment) {
        String field = segment;
        Integer index = null;
        int bracket = segment.indexOf('[');
        if (bracket > -1 && segment.endsWith("]")) {
            field = segment.substring(0, bracket);
            try {
                index = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        Object next = current;
        if (StringUtils.hasText(field)) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            next = map.get(field);
        }
        if (index != null) {
            if (!(next instanceof List<?> list) || index < 0 || index >= list.size()) {
                return null;
            }
            next = list.get(index);
        }
        return next;
    }

    private Map<String, Object> buildMcpMeta(String actionId, ActionAccessMode accessMode, ActionContext context) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfText(meta, "aiFabricActionId", actionId);
        if (accessMode != null) {
            meta.put("aiFabricAccessMode", accessMode.name());
        }
        if (context != null) {
            putIfText(meta, "requestId", context.requestId());
            putIfText(meta, "conversationId", context.conversationId());
            putIfText(meta, "userId", context.userId());
            putIfText(meta, "sessionId", context.sessionId());
            OrchestrationContext orchestrationContext = context.orchestrationContext();
            if (orchestrationContext != null && orchestrationContext.getMetadata() != null) {
                putIfText(meta, "shopDomain", firstText(orchestrationContext.getMetadata(), "shopDomain", "shop_domain"));
            }
        }
        return meta.isEmpty() ? null : Map.copyOf(meta);
    }

    private ActionResult toActionResult(McpActionSpec spec, McpSchema.CallToolResult mcpResult) {
        if (mcpResult == null) {
            return failure(ERROR_SERVICE_UNAVAILABLE, "Spring AI MCP client returned no result.");
        }
        if (Boolean.TRUE.equals(mcpResult.isError())) {
            return failure(ERROR_MCP_TOOL_EXECUTION_FAILED, "MCP tool returned an error.");
        }

        Map<String, Object> rawResult = rawResult(mcpResult);
        Object selected = selectResult(rawResult, spec.resultPath());
        if (resultCharacters(selected) > spec.maxResultCharacters()) {
            return failure(
                ERROR_MCP_RESULT_TOO_LARGE,
                "MCP tool result exceeded the configured safe response limit."
            );
        }
        ActionPayload payload;
        try {
            payload = toPayload(selected);
        } catch (Exception ex) {
            return failure(ERROR_MCP_TOOL_EXECUTION_FAILED, "MCP tool returned an invalid action payload: " + ex.getMessage());
        }
        return ActionResult.builder()
            .success(true)
            .message("MCP tool executed.")
            .data(payload)
            .build();
    }

    private int resultCharacters(Object selected) {
        if (selected == null) {
            return 0;
        }
        try {
            return objectMapper.writeValueAsString(selected).length();
        } catch (Exception ex) {
            return selected.toString().length();
        }
    }

    private Map<String, Object> rawResult(McpSchema.CallToolResult result) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("content", convertValue(result.content()));
        if (result.structuredContent() != null) {
            raw.put("structuredContent", convertValue(result.structuredContent()));
        }
        if (result.meta() != null && !result.meta().isEmpty()) {
            raw.put("meta", convertValue(result.meta()));
        }
        if (result.isError() != null) {
            raw.put("isError", result.isError());
        }
        return raw;
    }

    private Object selectResult(Map<String, Object> rawResult, String resultPath) {
        if (!StringUtils.hasText(resultPath)) {
            return rawResult;
        }
        String path = resultPath.trim();
        if ("$".equals(path)) {
            return rawResult;
        }
        if (!path.startsWith("$.")) {
            throw new IllegalStateException("responseMapping.resultPath must start with '$.'.");
        }
        return resolvePath(rawResult, path.substring(2));
    }

    private ActionPayload toPayload(Object selected) {
        if (selected == null) {
            return ActionPayload.object(Map.of());
        }
        if (selected instanceof List<?> list) {
            return ActionPayload.list(list);
        }
        if (selected instanceof Map<?, ?> map) {
            return ActionPayload.object(toStringKeyMap(map));
        }
        return ActionPayload.object(Map.of("result", selected));
    }

    private Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, new TypeReference<Object>() {});
        } catch (Exception ex) {
            return value.toString();
        }
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String firstText(Map<String, ?> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private ActionResult failure(String errorCode, String message) {
        return ActionResult.builder()
            .success(false)
            .errorCode(errorCode)
            .message(message)
            .build();
    }

    private record McpActionSpec(
        String serverRef,
        String toolName,
        Object argumentTemplate,
        String resultPath,
        int maxResultCharacters
    ) {
        @SuppressWarnings("unchecked")
        static McpActionSpec from(Map<String, Object> actionConfig) {
            Map<String, Object> execution = mapValue(actionConfig != null ? actionConfig.get("execution") : null);
            Map<String, Object> mcp = mapValue(execution.get("mcp"));
            Map<String, Object> responseMapping = mapValue(mcp.get("responseMapping"));
            return new McpActionSpec(
                textValue(mcp.get("serverRef")),
                textValue(mcp.get("toolName")),
                mcp.get("argumentTemplate"),
                textValue(responseMapping.get("resultPath")),
                maxResultCharacters(responseMapping.get("maxCharacters"))
            );
        }

        private static int maxResultCharacters(Object value) {
            if (value == null) {
                return DEFAULT_MAX_RESULT_CHARACTERS;
            }
            int parsed;
            try {
                parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(value.toString().trim());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                    "execution.mcp.responseMapping.maxCharacters must be an integer."
                );
            }
            if (parsed < MIN_MAX_RESULT_CHARACTERS
                || parsed > MAX_MAX_RESULT_CHARACTERS) {
                throw new IllegalArgumentException(
                    "execution.mcp.responseMapping.maxCharacters must be between "
                        + MIN_MAX_RESULT_CHARACTERS + " and "
                        + MAX_MAX_RESULT_CHARACTERS + "."
                );
            }
            return parsed;
        }

        private static Map<String, Object> mapValue(Object value) {
            if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return out;
        }

        private static String textValue(Object value) {
            if (value == null) {
                return null;
            }
            String text = value.toString();
            return StringUtils.hasText(text) ? text.trim() : null;
        }
    }
}
