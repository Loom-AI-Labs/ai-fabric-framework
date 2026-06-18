package ai.fabric.relationship.spi;

import ai.fabric.dto.AIAccessSubjectContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to demonstrate how to implement RelationshipQueryAccessControlPolicy.
 * 
 * This test shows example implementations for different access control scenarios.
 */
@DisplayName("RelationshipQueryAccessControlPolicy Interface")
class RelationshipQueryAccessControlPolicyTest {

    @Test
    @DisplayName("Example: Role-based access control implementation")
    void exampleRoleBasedAccessControl() {
        RelationshipQueryAccessControlPolicy policy = new RoleBasedAccessControlPolicy();

        // Admin can execute relationship queries
        assertThat(policy.canExecuteRelationshipQueries(user("admin-user"))).isTrue();
        
        // Regular user can execute relationship queries
        assertThat(policy.canExecuteRelationshipQueries(user("regular-user"))).isTrue();
        
        // Anonymous users cannot
        assertThat(policy.canExecuteRelationshipQueries(user(null))).isFalse();

        // Admin can query all entity types
        assertThat(policy.canQueryEntityType(user("admin-user"), "customer")).isTrue();
        assertThat(policy.canQueryEntityType(user("admin-user"), "order")).isTrue();
        assertThat(policy.canQueryEntityType(user("admin-user"), "product")).isTrue();

        // Regular user can only query specific entity types
        assertThat(policy.canQueryEntityType(user("regular-user"), "product")).isTrue();
        assertThat(policy.canQueryEntityType(user("regular-user"), "order")).isFalse();
        assertThat(policy.canQueryEntityType(user("regular-user"), "customer")).isFalse();

        // Admin gets all entity types
        List<String> adminTypes = policy.getAllowedEntityTypes(user("admin-user"));
        assertThat(adminTypes).containsExactlyInAnyOrder("customer", "order", "product");

        // Regular user gets limited entity types
        List<String> userTypes = policy.getAllowedEntityTypes(user("regular-user"));
        assertThat(userTypes).containsExactly("product");
    }

    @Test
    @DisplayName("Example: Permission-based access control implementation")
    void examplePermissionBasedAccessControl() {
        RelationshipQueryAccessControlPolicy policy = new PermissionBasedAccessControlPolicy();

        // User with permissions can query specific entity types
        assertThat(policy.canQueryEntityType(user("user-with-product-permission"), "product")).isTrue();
        assertThat(policy.canQueryEntityType(user("user-with-product-permission"), "order")).isFalse();

        assertThat(policy.canQueryEntityType(user("user-with-all-permissions"), "product")).isTrue();
        assertThat(policy.canQueryEntityType(user("user-with-all-permissions"), "order")).isTrue();
        assertThat(policy.canQueryEntityType(user("user-with-all-permissions"), "customer")).isTrue();
    }

    // ==================== Example Implementations ====================

    /**
     * Example implementation: Role-based access control
     */
    private static class RoleBasedAccessControlPolicy implements RelationshipQueryAccessControlPolicy {

        @Override
        public boolean canExecuteRelationshipQueries(AIAccessSubjectContext authContext) {
            // Anonymous users cannot execute relationship queries
            return subjectId(authContext).isPresent();
        }

        @Override
        public boolean canQueryEntityType(AIAccessSubjectContext authContext, String entityType) {
            return subjectId(authContext)
                .map(userId -> {
                    // Admin users can query all entity types
                    if (userId.startsWith("admin-")) {
                        return true;
                    }

                    // Regular users can only query "product" entity type
                    if (userId.startsWith("regular-")) {
                        return "product".equals(entityType);
                    }

                    return false;
                })
                .orElse(false);
        }

        @Override
        public List<String> getAllowedEntityTypes(AIAccessSubjectContext authContext) {
            return subjectId(authContext)
                .map(userId -> {
                    // Admin users get all entity types
                    if (userId.startsWith("admin-")) {
                        return List.of("customer", "order", "product");
                    }

                    // Regular users only get "product"
                    if (userId.startsWith("regular-")) {
                        return List.of("product");
                    }

                    return List.<String>of();
                })
                .orElse(List.of());
        }
    }

    /**
     * Example implementation: Permission-based access control
     */
    private static class PermissionBasedAccessControlPolicy implements RelationshipQueryAccessControlPolicy {

        // Simulated permission store
        private final java.util.Map<String, List<String>> userPermissions = java.util.Map.of(
            "user-with-product-permission", List.of("relationship_query:product"),
            "user-with-all-permissions", List.of("relationship_query:product", "relationship_query:order", "relationship_query:customer")
        );

        @Override
        public boolean canExecuteRelationshipQueries(AIAccessSubjectContext authContext) {
            return subjectId(authContext)
                .map(userPermissions::containsKey)
                .orElse(false);
        }

        @Override
        public boolean canQueryEntityType(AIAccessSubjectContext authContext, String entityType) {
            String permission = "relationship_query:" + entityType;
            return subjectId(authContext)
                .filter(userPermissions::containsKey)
                .map(userId -> userPermissions.get(userId).contains(permission))
                .orElse(false);
        }

        @Override
        public List<String> getAllowedEntityTypes(AIAccessSubjectContext authContext) {
            return subjectId(authContext)
                .filter(userPermissions::containsKey)
                .map(userId -> userPermissions.get(userId).stream()
                    .filter(perm -> perm.startsWith("relationship_query:"))
                    .map(perm -> perm.substring("relationship_query:".length()))
                    .toList())
                .orElse(List.of());
        }
    }

    private static AIAccessSubjectContext user(String userId) {
        return AIAccessSubjectContext.builder()
            .subjectId(userId)
            .subjectType(userId == null ? null : "END_USER")
            .build();
    }

    private static Optional<String> subjectId(AIAccessSubjectContext authContext) {
        if (authContext == null) {
            return Optional.empty();
        }
        if (authContext.getSubjectId() != null && !authContext.getSubjectId().isBlank()) {
            return Optional.of(authContext.getSubjectId());
        }
        if (authContext.getSessionId() != null && !authContext.getSessionId().isBlank()) {
            return Optional.of(authContext.getSessionId());
        }
        return Optional.empty();
    }
}
