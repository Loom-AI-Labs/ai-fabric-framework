package ai.fabric.indexing.document.model;

import java.util.Set;

/** Provider-neutral metadata owned by AI Fabric document indexing. */
public final class DocumentMetadataKeys {

    public static final String SOURCE_ID = "_aiDocumentSourceId";
    public static final String SOURCE_VERSION = "_aiDocumentSourceVersion";
    public static final String SOURCE_NAME = "_aiDocumentSourceName";
    public static final String DOCUMENT_ID = "_aiDocumentId";
    public static final String CHUNK_ID = "_aiDocumentChunkId";
    public static final String CHUNK_INDEX = "_aiDocumentChunkIndex";
    public static final String CHUNK_COUNT = "_aiDocumentChunkCount";
    public static final String CONTENT_FINGERPRINT =
        "_aiDocumentContentFingerprint";
    public static final String TENANT_ID = "_aiDocumentTenantId";
    public static final String VISIBILITY = "_aiDocumentVisibility";

    public static final Set<String> ALL = Set.of(
        SOURCE_ID,
        SOURCE_VERSION,
        SOURCE_NAME,
        DOCUMENT_ID,
        CHUNK_ID,
        CHUNK_INDEX,
        CHUNK_COUNT,
        CONTENT_FINGERPRINT,
        TENANT_ID,
        VISIBILITY
    );

    private DocumentMetadataKeys() {
    }
}
