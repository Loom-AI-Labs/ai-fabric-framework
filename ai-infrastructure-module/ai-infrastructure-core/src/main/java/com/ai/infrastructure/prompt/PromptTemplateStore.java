package com.ai.infrastructure.prompt;

/**
 * Storage interface for versioned prompt templates.
 */
public interface PromptTemplateStore {
    PromptTemplate load(PromptTemplateKey key);
}

