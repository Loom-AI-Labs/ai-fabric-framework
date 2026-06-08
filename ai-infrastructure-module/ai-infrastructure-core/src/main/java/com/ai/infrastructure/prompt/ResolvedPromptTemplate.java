package com.ai.infrastructure.prompt;

import java.util.List;
import java.util.Objects;

/**
 * A resolved prompt template along with deterministic resolution diagnostics.
 */
public record ResolvedPromptTemplate(
    PromptTemplate template,
    List<String> candidateVersions
) {
    public ResolvedPromptTemplate {
        Objects.requireNonNull(template, "template must not be null");
        candidateVersions = candidateVersions != null ? List.copyOf(candidateVersions) : List.of();
    }
}

