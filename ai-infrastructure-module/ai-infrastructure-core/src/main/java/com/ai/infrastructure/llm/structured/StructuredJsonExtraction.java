package com.ai.infrastructure.llm.structured;

/**
 * Result of extracting a JSON payload from an LLM response.
 *
 * <p>This is intentionally lightweight and does not attempt to "fix" JSON. It only extracts the most
 * plausible JSON envelope (object/array) and reports whether truncation is suspected.</p>
 */
public record StructuredJsonExtraction(
    String payload,
    boolean jsonFound,
    boolean truncationSuspected
) {

    public static StructuredJsonExtraction empty() {
        return new StructuredJsonExtraction(null, false, false);
    }
}

