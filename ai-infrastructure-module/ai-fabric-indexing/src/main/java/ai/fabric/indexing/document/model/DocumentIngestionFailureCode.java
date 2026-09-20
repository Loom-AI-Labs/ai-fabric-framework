package ai.fabric.indexing.document.model;

/** Stable public failure families for governed document ingestion. */
public enum DocumentIngestionFailureCode {
    DOCUMENT_RESOURCE_UNTRUSTED,
    DOCUMENT_PARSE_FAILED,
    DOCUMENT_LIMIT_EXCEEDED,
    DOCUMENT_METADATA_REJECTED,
    DOCUMENT_INDEXING_FAILED,
    DOCUMENT_DELETE_FAILED
}
