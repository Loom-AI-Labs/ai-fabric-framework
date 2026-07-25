package ai.fabric.indexing.api;

/**
 * Observable state returned when entity lifecycle work enters the indexing pipeline.
 */
public enum IndexingDispatchStatus {
    COMPLETED,
    QUEUED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    SKIPPED_STALE
}
