package com.ai.infrastructure.connector.rest.template;

import com.ai.infrastructure.connector.rest.api.ActionExecuteRequestDto;
import com.ai.infrastructure.connector.rest.service.RestActionExecutionService.ResolvedUpstream;
import com.ai.infrastructure.connector.rest.util.TraceContextSupport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, strict templating engine for routing config:
 * - Supports placeholders: {@code {{params.*}}}, {@code {{trace.*}}}, {@code {{body.*}}}, {@code {{status}}}.
 * - Supports a simple default value fallback with {@code |}: {@code {{params.items[0].quantity|1}}}.
 * - If the entire string is a single placeholder, the resolved value is returned as-is (type preserved).
 * - Otherwise placeholders are string-substituted.
 */
@Service
public class TemplateEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile(
        "\\{\\{\\s*([a-zA-Z0-9_\\.\\[\\]-]+(?:\\|[a-zA-Z0-9_\\.\\[\\]-]+)?)\\s*}}"
    );

    public Map<String, Object> contextFor(ActionExecuteRequestDto request, ResolvedUpstream upstream) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("actionId", request != null ? request.actionId() : null);
        ctx.put("idempotencyKey", request != null ? request.idempotencyKey() : null);
        ctx.put("params", request != null && request.params() != null ? request.params() : Map.of());
        ctx.put("trace", request != null && request.trace() != null ? TraceContextSupport.templateMap(request.trace()) : Map.of());
        ctx.put("upstream", upstream != null ? Map.of("baseUrl", upstream.baseUrl(), "path", upstream.path()) : Map.of());
        return ctx;
    }

    public Object resolve(Object template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        if (template instanceof String s) {
            return resolveString(s, context);
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().toString();
                Object value = resolve(entry.getValue(), context);
                out.put(key, value);
            }
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(resolve(item, context));
            }
            return out;
        }
        return template;
    }

    private Object resolveString(String template, Map<String, Object> context) {
        if (!StringUtils.hasText(template)) {
            return template;
        }

        Matcher m = PLACEHOLDER.matcher(template);
        if (isSinglePlaceholder(template, m)) {
            String expr = m.group(1);
            return eval(expr, context);
        }

        m.reset();
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String expr = m.group(1);
            Object value = eval(expr, context);
            String replacement = value != null ? value.toString() : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean isSinglePlaceholder(String template, Matcher matcher) {
        if (!matcher.find()) {
            return false;
        }
        if (matcher.start() != 0) {
            return false;
        }
        if (matcher.end() != template.length()) {
            return false;
        }
        return true;
    }

    private Object eval(String expr, Map<String, Object> context) {
        if (!StringUtils.hasText(expr)) {
            return null;
        }
        String e = expr.trim();

        // Fallback: {{path|default}} where default is a simple token (e.g. 1, true, demo-user).
        String defaultRaw = null;
        Object defaultValue = null;
        int pipe = e.indexOf('|');
        if (pipe >= 0) {
            defaultRaw = e.substring(pipe + 1).trim();
            e = e.substring(0, pipe).trim();
            defaultValue = parseDefaultLiteral(defaultRaw);
        }

        // Root access shortcut (e.g. {{status}})
        if (!e.contains(".")) {
            Object value = context != null ? context.get(e) : null;
            if (value == null || (value instanceof String s && !StringUtils.hasText(s))) {
                return defaultValue;
            }
            Object clamped = maybeClampInvalidNumber(value, defaultValue);
            if (clamped != null) {
                return clamped;
            }
            return value;
        }

        String[] parts = e.split("\\.", 2);
        String root = parts[0].trim();
        String path = parts[1].trim();
        Object rootObj = context != null ? context.get(root) : null;
        Object value = readPath(rootObj, path);
        if (value == null || (value instanceof String s && !StringUtils.hasText(s))) {
            return defaultValue;
        }
        Object clamped = maybeClampInvalidNumber(value, defaultValue);
        if (clamped != null) {
            return clamped;
        }
        return value;
    }

    /**
     * Small ergonomic improvement for demo connectors:
     * if a template provides a positive numeric default (e.g. {@code |1}) and the resolved value is
     * a non-positive number (0/-1), treat it as invalid and use the default instead.
     *
     * <p>This fixes common LLM outputs like {@code quantity: 0} which would otherwise fail upstream
     * validation.</p>
     */
    private Object maybeClampInvalidNumber(Object value, Object defaultValue) {
        if (!(value instanceof Number v) || !(defaultValue instanceof Number d)) {
            return null;
        }
        double dv = d.doubleValue();
        double vv = v.doubleValue();
        if (dv > 0 && vv <= 0) {
            return defaultValue;
        }
        return null;
    }

    private Object parseDefaultLiteral(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String r = raw.trim();
        if ("null".equalsIgnoreCase(r)) {
            return null;
        }
        if ("true".equalsIgnoreCase(r) || "false".equalsIgnoreCase(r)) {
            return Boolean.parseBoolean(r);
        }
        if (r.matches("^-?[0-9]+$")) {
            try {
                return Integer.parseInt(r);
            } catch (NumberFormatException ignored) {
                try {
                    return Long.parseLong(r);
                } catch (NumberFormatException ignoredAgain) {
                    return r;
                }
            }
        }
        if (r.matches("^-?[0-9]+\\.[0-9]+$")) {
            try {
                return Double.parseDouble(r);
            } catch (NumberFormatException ignored) {
                return r;
            }
        }
        if ((r.startsWith("\"") && r.endsWith("\"") && r.length() >= 2)
            || (r.startsWith("'") && r.endsWith("'") && r.length() >= 2)) {
            return r.substring(1, r.length() - 1);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private Object readPath(Object root, String path) {
        if (root == null || !StringUtils.hasText(path)) {
            return null;
        }

        String remaining = path;
        Object current = root;

        while (StringUtils.hasText(remaining)) {
            int dot = remaining.indexOf('.');
            String segment = dot >= 0 ? remaining.substring(0, dot) : remaining;
            remaining = dot >= 0 ? remaining.substring(dot + 1) : null;

            segment = segment.trim();
            if (segment.isEmpty()) {
                continue;
            }

            // Handle optional [index] suffix (e.g. items[0])
            String key = segment;
            Integer index = null;
            int bracket = segment.indexOf('[');
            if (bracket >= 0 && segment.endsWith("]")) {
                key = segment.substring(0, bracket).trim();
                String rawIndex = segment.substring(bracket + 1, segment.length() - 1).trim();
                if (rawIndex.matches("^[0-9]+$")) {
                    index = Integer.parseInt(rawIndex);
                }
            }

            if (StringUtils.hasText(key)) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(key);
                } else {
                    return null;
                }
            }

            if (index != null) {
                if (current instanceof List<?> list) {
                    if (index < 0 || index >= list.size()) {
                        return null;
                    }
                    current = list.get(index);
                } else {
                    return null;
                }
            }

            if (remaining == null) {
                break;
            }
        }

        return current;
    }
}
