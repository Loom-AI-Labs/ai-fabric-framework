package ai.fabric.llm.structured;

public enum StructuredJsonFailureType {
    CALL_ERROR,
    EMPTY_RESPONSE,
    TRUNCATED_RESPONSE,
    NO_JSON_FOUND,
    PARSE_ERROR,
    VALIDATION_ERROR
}

