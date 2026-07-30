package ai.fabric.indexing.api;

import java.util.Optional;

/**
 * Read-only lookup for durable indexing work submitted through AI Fabric.
 *
 * <p>The returned status is an in-process framework contract. Applications
 * remain responsible for authentication, authorization, and tenant checks
 * before exposing it through an HTTP or messaging transport.</p>
 */
@FunctionalInterface
public interface IndexingWorkQuery {

    /**
     * Finds one work item by the opaque ID returned in {@link IndexingOutcome}.
     *
     * @param workId work ID returned by an indexing submission
     * @return the sanitized status, or empty when the work item does not exist
     * @throws IllegalArgumentException when the ID is blank or malformed
     */
    Optional<IndexingWorkStatus> findByWorkId(String workId);
}
