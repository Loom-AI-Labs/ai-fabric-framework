package com.ai.infrastructure.llm.structured;

import java.util.Map;

/**
 * Provider-agnostic hints for requesting structured JSON responses.
 *
 * <p>Some providers (OpenAI/Azure) support {@code response_format}. Others may ignore it, but still benefit from
 * explicit "JSON-only" prompt contracts.</p>
 */
public final class StructuredJsonProviderHints {

    private StructuredJsonProviderHints() {
    }

    public static Map<String, Object> jsonObjectResponseParameters() {
        return Map.of(
            "response_format", Map.of("type", "json_object")
        );
    }
}

