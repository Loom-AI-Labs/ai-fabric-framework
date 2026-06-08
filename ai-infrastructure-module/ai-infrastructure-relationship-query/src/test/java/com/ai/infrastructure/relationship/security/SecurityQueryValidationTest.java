package com.ai.infrastructure.relationship.security;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import com.ai.infrastructure.relationship.service.EntityRelationshipMapper;
import com.ai.infrastructure.relationship.validation.RelationshipQueryValidationException;
import com.ai.infrastructure.relationship.validation.RelationshipQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityQueryValidationTest {

    private RelationshipQueryValidator validator;
    private EntityRelationshipMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EntityRelationshipMapper();
        mapper.registerEntityType(SecureEntity.class);
        validator = new RelationshipQueryValidator(mapper);
    }

    @Test
    void shouldBlockDropTableAttempt() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("DROP TABLE documents; -- remove everything")
            .primaryEntityType("secure-entity")
            .build();

        assertThatThrownBy(() -> validator.validate(plan))
            .isInstanceOf(RelationshipQueryValidationException.class)
            .hasMessageContaining("injection");
    }

    @Test
    void shouldBlockUnionSelectAttempt() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Show contracts UNION SELECT password FROM users")
            .primaryEntityType("secure-entity")
            .build();

        assertThatThrownBy(() -> validator.validate(plan))
            .isInstanceOf(RelationshipQueryValidationException.class)
            .hasMessageContaining("injection");
    }

    @Test
    void shouldBlockBooleanTautologyAttempt() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("'; OR 1=1 -- full dump")
            .primaryEntityType("secure-entity")
            .build();

        assertThatThrownBy(() -> validator.validate(plan))
            .isInstanceOf(RelationshipQueryValidationException.class)
            .hasMessageContaining("injection");
    }

    @Test
    void shouldAllowBenignNaturalLanguageQuery() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Find high-risk payments for compliance review")
            .primaryEntityType("secure-entity")
            .build();

        assertThatCode(() -> validator.validate(plan))
            .doesNotThrowAnyException();
    }

    @AICapable(entityType = "secure-entity")
    private static class SecureEntity { }
}
