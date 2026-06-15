package ai.fabric.prompt;

/**
 * Storage interface for versioned prompt templates.
 */
public interface PromptTemplateStore {
    PromptTemplate load(PromptTemplateKey key);
}

