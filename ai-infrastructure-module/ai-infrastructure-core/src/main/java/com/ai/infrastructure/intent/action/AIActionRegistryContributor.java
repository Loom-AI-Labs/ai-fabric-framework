package com.ai.infrastructure.intent.action;

import java.util.List;

/**
 * Contributor SPI for adding actions to the {@link AIActionRegistry} from non-annotation sources.
 *
 * <p>Examples include file-based connector action catalogs and DB-backed action catalogs.</p>
 */
public interface AIActionRegistryContributor {

    /**
     * Provide additional action handlers to register.
     *
     * <p>Greenfield: contributors must return fully-validated handlers. Any schema issues should fail fast
     * during contributor initialization or during this call.</p>
     *
     * @return list of additional handlers (never null)
     */
    List<AIActionHandler> getHandlers();

    /**
     * Human-readable source name used for duplicate detection messages.
     *
     * @return source label (never null)
     */
    default String getSourceName() {
        return getClass().getSimpleName();
    }
}

