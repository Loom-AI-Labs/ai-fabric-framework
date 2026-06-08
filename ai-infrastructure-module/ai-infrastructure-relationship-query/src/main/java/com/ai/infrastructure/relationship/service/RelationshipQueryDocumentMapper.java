package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.dto.RAGResponse;

import java.util.Optional;

/**
 * Maps relational query results (JPA entities) into {@link RAGResponse.RAGDocument} objects.
 */
public interface RelationshipQueryDocumentMapper {

    Optional<RAGResponse.RAGDocument> map(String entityType, Object entity, String entityId);
}

