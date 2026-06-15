package ai.fabric.relationship.service;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.model.QueryOptions;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Simple wrapper around {@link LLMDrivenJPAQueryService}.
 *
 * <p>This module previously exposed multiple fallback stages (metadata traversal, vector fallback, etc.).
 * The current implementation favors simplicity and materializes directly from relational query results.</p>
 */
public class ReliableRelationshipQueryService {

    private final LLMDrivenJPAQueryService primaryService;

    public ReliableRelationshipQueryService(LLMDrivenJPAQueryService primaryService) {
        this.primaryService = Objects.requireNonNull(primaryService, "primaryService is required");
    }

    public RAGResponse execute(String query) {
        return execute(query, null, null);
    }

    public RAGResponse execute(String query, @Nullable QueryOptions options) {
        return execute(query, null, options);
    }

    public RAGResponse execute(String query, @Nullable List<String> entityTypes, @Nullable QueryOptions options) {
        return primaryService.executeRelationshipQuery(
            query,
            entityTypes,
            options != null ? options : QueryOptions.defaults()
        );
    }
}
