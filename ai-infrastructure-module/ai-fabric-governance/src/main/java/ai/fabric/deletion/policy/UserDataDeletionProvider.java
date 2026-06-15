package ai.fabric.deletion.policy;

import java.util.List;

/**
 * Infrastructure hook for right-to-delete orchestration. Customer implementations own the
 * domain specific deletion of user data.
 */
public interface UserDataDeletionProvider {

    /**
     * Determine if the user can be deleted (e.g. no active orders).
     *
     * @param userId identifier of the user
     * @return {@code true} when deletion may proceed
     */
    boolean canDeleteUser(String userId);

    /**
     * Delete or anonymise any domain data linked to the user.
     *
     * @param userId identifier of the user
     * @return number of records that were deleted or anonymised
     */
    int deleteUserDomainData(String userId);

    /**
     * Notify downstream systems that the user has been deleted.
     *
     * @param userId identifier of the deleted user
     */
    void notifyAfterDeletion(String userId);

    /**
     * Optional hook allowing providers to supply references to indexed entities that should be removed
     * from the vector store as part of the deletion request.
     *
     * @param userId identifier of the deleted user
     * @return list of entity references (entityType/entityId) to purge, defaults to an empty list
     */
    default List<UserEntityReference> findIndexedEntities(String userId) {
        return List.of();
    }

    /**
     * Simple record describing an indexed entity to remove.
     *
     * @param entityType logical entity type
     * @param entityId unique identifier for the entity
     */
    record UserEntityReference(String entityType, String entityId) { }
}

