package ai.fabric.indexing.document.model;

/** Safe warning codes produced while preparing document chunks. */
public enum DocumentIngestionWarningCode {
    METADATA_KEY_DROPPED,
    METADATA_VALUE_TRUNCATED,
    METADATA_LIMIT_REACHED,
    PREVIEW_CONTENT_BOUNDED,
    PARSER_DOCUMENT_SKIPPED_EMPTY,
    TRANSFORMER_DOCUMENT_SKIPPED_EMPTY
}
