package ai.fabric.intent.action.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded JSON projection of approved action output, never transport or execution context. */
final class ConnectorAutomaticEvidence {
    static final int MAX_CHARACTERS = 12000;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 256;
    private static final int MAX_ITEMS = 10;
    private static final int MAX_FIELDS = 32;
    private static final int MAX_STRING_CHARACTERS = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();
    // Credential/transport field identifiers are a security boundary, not semantic text matching.
    private static final Set<String> PRIVATE_FIELDS = Set.of(
        "authorization", "proxyauthorization", "cookie", "setcookie", "headers", "requestheaders", "responseheaders",
        "apikey", "xapikey", "accesskey", "accesskeyid", "secretaccesskey", "secretkey", "password", "passwd",
        "clientsecret", "privatekey", "accesstoken", "refreshtoken", "idtoken", "bearertoken", "sessiontoken",
        "token", "secret", "credentials", "credential", "securitykey", "signingkey", "encryptionkey"
    );

    record Projection(Object data, boolean truncated) { }

    private final Set<String> excluded;
    private int characters = MAX_CHARACTERS;
    private int nodes = MAX_NODES;
    private boolean truncated;

    private ConnectorAutomaticEvidence(Set<String> sensitiveFields) {
        excluded = new LinkedHashSet<>(PRIVATE_FIELDS);
        if (sensitiveFields != null) sensitiveFields.forEach(field -> excluded.add(normalize(field)));
    }

    static Projection project(Map<String, Object> approvedOutput, Set<String> sensitiveFields) {
        var projection = new ConnectorAutomaticEvidence(sensitiveFields);
        Object data = projection.value(approvedOutput, 0);
        return new Projection(data, projection.truncated);
    }

    private Object value(Object input, int depth) {
        if (--nodes < 0 || depth > MAX_DEPTH) {
            truncated = true;
            return null;
        }
        if (input == null || input instanceof Boolean || input instanceof Number) {
            if (take(jsonLength(input))) return input;
            return null;
        }
        if (input instanceof String text) {
            int end = Math.min(text.length(), MAX_STRING_CHARACTERS);
            String result = text.substring(0, end);
            while (end > 0 && jsonLength(result) > characters) {
                end /= 2;
                result = text.substring(0, end);
            }
            if (end < text.length()) truncated = true;
            return take(jsonLength(result)) ? result : null;
        }
        if (input instanceof Map<?, ?> map) {
            if (!take(2)) return null;
            Map<String, Object> output = new LinkedHashMap<>();
            int visited = 0;
            for (var entry : map.entrySet()) {
                if (++visited > MAX_FIELDS) {
                    truncated = true;
                    break;
                }
                if (!(entry.getKey() instanceof String key) || excluded.contains(normalize(key))) continue;
                if (output.size() >= MAX_FIELDS || nodes <= 0 || key.length() > 128
                    || !take(jsonLength(key) + 2 + 4)) {
                    truncated = true;
                    break;
                }
                Object item = value(entry.getValue(), depth + 1);
                output.put(key, item);
            }
            return Collections.unmodifiableMap(output);
        }
        if (input instanceof List<?> list) {
            if (!take(2)) return null;
            List<Object> output = new ArrayList<>();
            for (Object item : list) {
                if (output.size() >= MAX_ITEMS || nodes <= 0 || !take(5)) {
                    truncated = true;
                    break;
                }
                output.add(value(item, depth + 1));
            }
            return Collections.unmodifiableList(output);
        }
        // Do not invoke arbitrary toString implementations or flatten deep objects into unbounded text.
        truncated = true;
        return null;
    }

    private boolean take(int amount) {
        if (amount > characters) {
            truncated = true;
            return false;
        }
        characters -= amount;
        return true;
    }

    private int jsonLength(Object value) {
        try {
            return JSON.writeValueAsString(value).length();
        } catch (JsonProcessingException exception) {
            truncated = true;
            return MAX_CHARACTERS + 1;
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
