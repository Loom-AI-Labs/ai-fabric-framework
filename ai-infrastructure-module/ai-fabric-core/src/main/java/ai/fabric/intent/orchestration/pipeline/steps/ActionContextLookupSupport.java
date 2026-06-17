package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionMetadataVisibilitySupport.getMetadataValueIgnoreCase;

final class ActionContextLookupSupport {

    private ActionContextLookupSupport() {
    }

    static Set<String> collectActionParameterNames(AIActionMetaData meta) {
        if (meta == null) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (meta.getParameterSchemas() != null) {
            meta.getParameterSchemas().keySet().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(names::add);
        }
        if (meta.getRequiredParameters() != null) {
            meta.getRequiredParameters().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(names::add);
        }
        if (meta.getParameters() != null) {
            meta.getParameters().keySet().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(names::add);
        }
        return Collections.unmodifiableSet(names);
    }

    static List<String> resolveResultPaths(String parameter, Map<String, Object> resolveFrom) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (resolveFrom != null) {
            addTextOrListValues(paths, resolveFrom.get("resultPaths"));
            addTextOrListValues(paths, resolveFrom.get("candidatePaths"));
            addTextOrListValues(paths, resolveFrom.get("resultPath"));
        }
        attachmentContextCandidateKeys(parameter).forEach(paths::add);
        return List.copyOf(paths);
    }

    static Object valueByPath(Object raw, String path) {
        if (raw == null || !StringUtils.hasText(path)) {
            return null;
        }
        Object current = raw;
        for (String segment : path.trim().split("\\.")) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = valueByCandidateKeys(map, List.of(segment.trim()));
                continue;
            }
            if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(segment.trim());
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ex) {
                    current = firstValueByCandidateKeys(list, List.of(segment.trim()));
                }
                continue;
            }
            return null;
        }
        return current;
    }

    static Object firstValueByCandidateKeys(List<?> list, List<String> candidateKeys) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (Object item : list) {
            Object value = valueByCandidateKeys(item, candidateKeys);
            if (ActionBatchSupport.hasMeaningfulBatchValue(value)) {
                return value;
            }
        }
        return null;
    }

    static List<String> attachmentContextCandidateKeys(String required) {
        String normalized = required.trim();
        List<String> keys = new ArrayList<>();
        keys.add(normalized);
        String camel = snakeToCamel(normalized);
        if (!camel.equals(normalized)) {
            keys.add(camel);
        }
        if (normalized.endsWith("_id")) {
            String base = normalized.substring(0, normalized.length() - "_id".length());
            String baseCamel = snakeToCamel(base);
            keys.add(baseCamel + "Id");
            keys.add(baseCamel + "ID");
            keys.add(baseCamel + "Gid");
        }
        return keys.stream()
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    static String snakeToCamel(String value) {
        if (!StringUtils.hasText(value) || !value.contains("_")) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        boolean upperNext = false;
        for (char ch : value.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return sb.toString();
    }

    static String metadataValueByCandidateKeys(Map<String, String> metadata, List<String> candidateKeys) {
        if (metadata == null || metadata.isEmpty() || candidateKeys == null || candidateKeys.isEmpty()) {
            return null;
        }
        for (String key : candidateKeys) {
            String value = getMetadataValueIgnoreCase(metadata, key);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    static List<String> resolveParamCandidateKeys(String parameter, Map<String, Object> resolveFrom) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (resolveFrom != null && !resolveFrom.isEmpty()) {
            addTextOrListValues(keys, resolveFrom.get("metadataKeys"));
            addTextOrListValues(keys, resolveFrom.get("candidateKeys"));
            addTextOrListValues(keys, resolveFrom.get("keys"));
            addTextOrListValues(keys, resolveFrom.get("handleField"));
            addTextOrListValues(keys, resolveFrom.get("field"));
        }
        attachmentContextCandidateKeys(parameter).stream()
            .filter(StringUtils::hasText)
            .forEach(keys::add);
        return List.copyOf(keys);
    }

    static void addTextOrListValues(LinkedHashSet<String> out, Object raw) {
        if (out == null || raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String value = stringObject(item);
                if (StringUtils.hasText(value)) {
                    out.add(value.trim());
                }
            }
            return;
        }
        String value = stringObject(raw);
        if (StringUtils.hasText(value)) {
            out.add(value.trim());
        }
    }

    static Object valueByCandidateKeys(Object raw, List<String> candidateKeys) {
        if (!(raw instanceof Map<?, ?> map) || candidateKeys == null || candidateKeys.isEmpty()) {
            return null;
        }
        for (String candidateKey : candidateKeys) {
            if (!StringUtils.hasText(candidateKey)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                if (candidateKey.trim().equalsIgnoreCase(entry.getKey().toString().trim())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    static String stringObject(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    static String firstTextObject(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = stringObject(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }
}
