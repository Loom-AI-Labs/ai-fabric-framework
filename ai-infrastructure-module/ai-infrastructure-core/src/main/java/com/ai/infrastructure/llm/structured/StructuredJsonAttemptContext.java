package com.ai.infrastructure.llm.structured;

import java.util.List;

public record StructuredJsonAttemptContext(
    int attemptIndex,
    List<StructuredJsonFailure> failures,
    String previousRawContent,
    String previousExtractedJson
) {
}

