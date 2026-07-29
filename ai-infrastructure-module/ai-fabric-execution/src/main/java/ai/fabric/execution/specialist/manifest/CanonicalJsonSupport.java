package ai.fabric.execution.specialist.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

public final class CanonicalJsonSupport {

    private final ObjectMapper objectMapper;

    public CanonicalJsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        );
    }

    public JsonNode canonicalize(JsonNode input) {
        Objects.requireNonNull(input, "input is required");
        if (input.isObject()) {
            ObjectNode output = objectMapper.createObjectNode();
            input.properties()
                .stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(entry ->
                    output.set(entry.getKey(), canonicalize(entry.getValue()))
                );
            return output;
        }
        if (input.isArray()) {
            ArrayNode output = objectMapper.createArrayNode();
            input.forEach(value -> output.add(canonicalize(value)));
            return output;
        }
        return input.deepCopy();
    }

    public String write(JsonNode input) {
        try {
            return objectMapper.writeValueAsString(canonicalize(input));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                "JSON could not be rendered canonically",
                ex
            );
        }
    }

    public String hash(JsonNode input) {
        return sha256(write(input));
    }

    public String hashValue(Object value) {
        return hash(objectMapper.valueToTree(value));
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
