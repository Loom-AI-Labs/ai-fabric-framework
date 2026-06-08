package com.ai.infrastructure.relationship.validation;

import com.ai.infrastructure.relationship.dto.RelationshipPath;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import com.ai.infrastructure.relationship.model.RelationshipMapping;
import com.ai.infrastructure.relationship.service.EntityRelationshipMapper;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Performs guardrail validation on {@link RelationshipQueryPlan} instances before execution.
 */
public class RelationshipQueryValidator {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("(?i)\\bdrop\\s+table\\b"),
        Pattern.compile("(?i)\\bdelete\\s+from\\b"),
        Pattern.compile("(?i)\\btruncate\\s+table\\b"),
        Pattern.compile("(?i)\\balter\\s+table\\b"),
        Pattern.compile("(?i)\\binsert\\s+into\\b"),
        Pattern.compile("(?i)\\bupdate\\s+\\w+\\s+set\\b"),
        Pattern.compile("(?i)\\bunion\\s+select\\b"),
        Pattern.compile("(?i)(?:'|\\b)\\s*or\\s*1=1"),
        Pattern.compile("(?i);\\s*--"),
        Pattern.compile("(?i)information_schema")
    );

    private final EntityRelationshipMapper relationshipMapper;

    public RelationshipQueryValidator(EntityRelationshipMapper relationshipMapper) {
        this.relationshipMapper = relationshipMapper;
    }

    public void validate(RelationshipQueryPlan plan) {
        validate(plan, ValidateMode.STRICT);
    }

    public void validate(RelationshipQueryPlan plan, ValidateMode mode) {
        if (plan == null) {
            throw new RelationshipQueryValidationException("Relationship query plan cannot be null");
        }

        if (!StringUtils.hasText(plan.getOriginalQuery())) {
            throw new RelationshipQueryValidationException("Original query text is required");
        }

        guardAgainstInjection(plan.getOriginalQuery());

        if (!StringUtils.hasText(plan.getPrimaryEntityType())) {
            throw new RelationshipQueryValidationException("Primary entity type is required");
        }

        if (!relationshipMapper.hasEntityType(plan.getPrimaryEntityType())) {
            throw new RelationshipQueryValidationException(
                "Primary entity type '%s' is not registered".formatted(plan.getPrimaryEntityType())
            );
        }

        List<String> candidateEntities = plan.getCandidateEntityTypes();
        if (!CollectionUtils.isEmpty(candidateEntities)) {
            boolean containsPrimary = candidateEntities.stream()
                .map(type -> type == null ? null : type.toLowerCase(Locale.ROOT))
                .anyMatch(type -> type != null && type.equals(plan.getPrimaryEntityType().toLowerCase(Locale.ROOT)));
            if (!containsPrimary) {
                throw new RelationshipQueryValidationException(
                    "Candidate entity list must contain the primary entity type '%s'"
                        .formatted(plan.getPrimaryEntityType())
                );
            }
        }

        validateRelationshipPaths(plan, mode);
    }

    private void guardAgainstInjection(String queryText) {
        String probe = queryText == null ? "" : queryText.trim();
        if (!StringUtils.hasText(probe)) {
            return;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(probe).find()) {
                throw new RelationshipQueryValidationException("Potential injection attempt detected in query text");
            }
        }
    }

    private void validateRelationshipPaths(RelationshipQueryPlan plan, ValidateMode mode) {
        if (CollectionUtils.isEmpty(plan.getRelationshipPaths())) {
            return;
        }

        for (RelationshipPath path : plan.getRelationshipPaths()) {
            if (path == null) {
                continue;
            }
            if (!StringUtils.hasText(path.getFromEntityType()) || !StringUtils.hasText(path.getToEntityType())) {
                throw new RelationshipQueryValidationException("Relationship paths must define from/to entity types");
            }
            if (!StringUtils.hasText(path.getRelationshipType()) && mode != ValidateMode.LAX) {
                throw new RelationshipQueryValidationException(
                    "Relationship path %s -> %s does not define a relationshipType"
                        .formatted(path.getFromEntityType(), path.getToEntityType())
                );
            }

            if (mode != ValidateMode.LAX) {
                if (!relationshipMapper.hasEntityType(path.getFromEntityType())) {
                    throw new RelationshipQueryValidationException(
                        "Relationship path fromEntityType '%s' is not registered".formatted(path.getFromEntityType())
                    );
                }
                if (!relationshipMapper.hasEntityType(path.getToEntityType())) {
                    throw new RelationshipQueryValidationException(
                        "Relationship path toEntityType '%s' is not registered".formatted(path.getToEntityType())
                    );
                }

                // relationshipType is the JPA relationship field name. Validate it matches the registered mapping.
                if (StringUtils.hasText(path.getRelationshipType())) {
                    try {
                        RelationshipMapping mapping = relationshipMapper.getRelationshipMapping(
                            path.getFromEntityType(),
                            path.getRelationshipType()
                        );
                        if (mapping != null && StringUtils.hasText(mapping.toEntityType())
                            && !mapping.toEntityType().equalsIgnoreCase(path.getToEntityType())) {
                            boolean sameBackingClass = false;
                            try {
                                String expectedClassName = relationshipMapper.getEntityClassName(mapping.toEntityType());
                                String providedClassName = relationshipMapper.getEntityClassName(path.getToEntityType());
                                sameBackingClass = expectedClassName != null && expectedClassName.equals(providedClassName);
                            } catch (Exception ignored) {
                                // fall through to strict mismatch error below
                            }

                            if (sameBackingClass) {
                                continue;
                            }
                            throw new RelationshipQueryValidationException(
                                "Relationship path %s.%s must target '%s' (got '%s')"
                                    .formatted(
                                        path.getFromEntityType(),
                                        path.getRelationshipType(),
                                        mapping.toEntityType(),
                                        path.getToEntityType()
                                    )
                            );
                        }
                    } catch (IllegalArgumentException ex) {
                        throw new RelationshipQueryValidationException(
                            "Relationship path %s.%s is not registered"
                                .formatted(path.getFromEntityType(), path.getRelationshipType())
                        );
                    }
                }
            }
        }
    }

    public enum ValidateMode {
        STRICT,
        LAX
    }
}
