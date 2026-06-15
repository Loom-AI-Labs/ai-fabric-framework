package ai.fabric.relationship.spi;

import ai.fabric.dto.AIAccessSubjectContext;

import java.util.List;

/**
 * SPI (Service Provider Interface) for controlling access to relationship queries.
 * 
 * Framework users implement this interface to provide their own access control logic.
 * The framework's {@link ai.fabric.relationship.action.RelationshipQueryActionHandler}
 * automatically discovers and uses implementations via Spring's dependency injection.
 * 
 * <p>Example implementation:</p>
 * <pre>{@code
 * @Component
 * @RequiredArgsConstructor
 * public class MyRelationshipQueryAccessControlPolicy 
 *         implements RelationshipQueryAccessControlPolicy {
 *     
 *     private final PermissionService permissionService;
 *     
 *     @Override
 *     public boolean canExecuteRelationshipQueries(AIAccessSubjectContext authContext) {
 *         return permissionService.hasPermission(authContext.getSubjectId(), "relationship_query:execute");
 *     }
 *     
 *     @Override
 *     public boolean canQueryEntityType(AIAccessSubjectContext authContext, String entityType) {
 *         return permissionService.hasPermission(authContext.getSubjectId(), "relationship_query:" + entityType);
 *     }
 *     
 *     @Override
 *     public List<String> getAllowedEntityTypes(AIAccessSubjectContext authContext) {
 *         return permissionService.getEntityTypesWithPermission(authContext.getSubjectId(), "relationship_query:");
 *     }
 * }
 * }</pre>
 * 
 * <p><strong>REQUIREMENT:</strong> This interface MUST be implemented when orchestrator
 * integration is enabled ({@code enable-orchestrator-integration=true}). The application will
 * fail to start if no implementation is provided.</p>
 * 
 * <p><strong>Note:</strong> When orchestrator integration is disabled ({@code enable-orchestrator-integration=false}),
 * this policy is NOT required. In standalone mode, users handle access control manually when
 * using {@link ai.fabric.relationship.service.ReliableRelationshipQueryService} directly.</p>
 */
public interface RelationshipQueryAccessControlPolicy {

    /**
     * Check if the user can execute relationship queries at all.
     * 
     * This is a general permission check - if this returns false, the user
     * cannot execute any relationship queries regardless of entity type.
     * 
     * @param authContext Canonical request auth context (may represent an anonymous session)
     * @return true if user can execute relationship queries, false otherwise
     */
    boolean canExecuteRelationshipQueries(AIAccessSubjectContext authContext);

    /**
     * Check if the user can query a specific entity type.
     * 
     * This method is called for each entity type in the query to determine
     * which entity types the user is allowed to access.
     * 
     * @param authContext Canonical request auth context (may represent an anonymous session)
     * @param entityType Entity type to check (e.g., "customer", "order", "product")
     * @return true if user can query this entity type, false otherwise
     */
    boolean canQueryEntityType(AIAccessSubjectContext authContext, String entityType);

    /**
     * Get all entity types the user is allowed to query.
     * 
     * This is used when no entity types are specified in the query,
     * to determine which entity types should be available for auto-detection.
     * 
     * @param authContext Canonical request auth context (may represent an anonymous session)
     * @return List of entity types the user can query, or empty list if none
     */
    List<String> getAllowedEntityTypes(AIAccessSubjectContext authContext);
}
