package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.relationship.dto.JpqlQuery;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Executes relationship traversals by running the generated JPQL against the live persistence context.
 */
public class JpaRelationshipTraversalService implements RelationshipTraversalService {

    private final EntityManager entityManager;

    public JpaRelationshipTraversalService(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager is required");
    }

    @Override
    public TraversalMode getMode() {
        return TraversalMode.JPA;
    }

    @Override
    public boolean supports(RelationshipQueryPlan plan) {
        return plan != null && entityManager != null;
    }

    @Override
    public TraversalResult traverse(RelationshipQueryPlan plan, JpqlQuery query) {
        if (!supports(plan) || query == null || query.getJpql() == null) {
            return TraversalResult.empty();
        }

        Query jpaQuery = entityManager.createQuery(query.getJpql());
        for (Map.Entry<String, Object> entry : query.getParameters().entrySet()) {
            jpaQuery.setParameter(entry.getKey(), entry.getValue());
        }
        if (query.getLimit() != null) {
            jpaQuery.setMaxResults(query.getLimit());
        }

        List<?> results = jpaQuery.getResultList();
        if (results.isEmpty()) {
            return TraversalResult.empty();
        }

        PersistenceUnitUtil util = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        List<Object> entities = new ArrayList<>(results.size());
        List<String> entityIds = new ArrayList<>(results.size());
        for (Object entity : results) {
            Object identifier = util.getIdentifier(entity);
            if (identifier != null) {
                entities.add(entity);
                entityIds.add(identifier.toString());
            }
        }

        return TraversalResult.entities(entities, entityIds);
    }
}
