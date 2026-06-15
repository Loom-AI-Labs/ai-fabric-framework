package ai.fabric.migration.domain;

/**
 * Lifecycle states for a migration job.
 */
public enum MigrationStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
