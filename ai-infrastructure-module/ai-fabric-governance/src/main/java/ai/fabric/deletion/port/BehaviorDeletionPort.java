package ai.fabric.deletion.port;

import java.util.UUID;

/**
 * Optional port that allows a behavior module to participate in GDPR/CCPA deletion workflows.
 */
public interface BehaviorDeletionPort {

    /**
     * Delete behavior history for the supplied user identifier.
     *
     * @param userId user identifier
     * @return number of behavior records deleted
     */
    int deleteUserBehaviors(UUID userId);
}

