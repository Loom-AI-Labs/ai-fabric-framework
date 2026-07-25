package ai.fabric.indexing.api;

import ai.fabric.indexing.model.AIIndexDocument;

/**
 * Optional application or framework handler for explicit entity analysis work.
 */
@FunctionalInterface
public interface AIIndexAnalysisHandler {

    String analyze(AIIndexDocument document);
}
