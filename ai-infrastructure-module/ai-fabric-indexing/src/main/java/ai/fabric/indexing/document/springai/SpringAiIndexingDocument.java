package ai.fabric.indexing.document.springai;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.IndexingStrategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Queue payload type used by the Spring AI document ingestion adapter.
 */
@AICapable(entityType = "spring-ai-document", indexingStrategy = IndexingStrategy.ASYNC)
public class SpringAiIndexingDocument {

    @AIContext(contextKey = "_springAiChunkId", dataType = "id")
    private String id;

    @AISearchable(preprocessing = "none", maxLength = 8000)
    private String content;

    @AIContext(contextKey = "_springAiSourceId", dataType = "id")
    private String sourceId;

    @AIContext(contextKey = "_springAiSourceName")
    private String sourceName;

    @AIContext(contextKey = "_springAiDocumentId", dataType = "id")
    private String documentId;

    @AIContext(contextKey = "_springAiChunkIndex", dataType = "number")
    private Integer chunkIndex;

    @AIContext(contextKey = "_springAiChunkCount", dataType = "number")
    private Integer chunkCount;

    @AIContext(contextKey = "_springAiContentFingerprint")
    private String contentFingerprint;

    @AIContext(contextKey = "_springAiMetadataDroppedCount", dataType = "number")
    private Integer metadataDroppedCount;

    @AIContext(contextKey = "_springAiDocumentMetadata", dataType = "json", includeInResponse = false)
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public SpringAiIndexingDocument() {
    }

    public SpringAiIndexingDocument(String id,
                                    String content,
                                    String sourceId,
                                    String sourceName,
                                    String documentId,
                                    Integer chunkIndex,
                                    Integer chunkCount,
                                    String contentFingerprint,
                                    Integer metadataDroppedCount,
                                    Map<String, Object> metadata) {
        this.id = id;
        this.content = content;
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.contentFingerprint = contentFingerprint;
        this.metadataDroppedCount = metadataDroppedCount;
        setMetadata(metadata);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint) {
        this.contentFingerprint = contentFingerprint;
    }

    public Integer getMetadataDroppedCount() {
        return metadataDroppedCount;
    }

    public void setMetadataDroppedCount(Integer metadataDroppedCount) {
        this.metadataDroppedCount = metadataDroppedCount;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpringAiIndexingDocument that)) {
            return false;
        }
        return Objects.equals(id, that.id)
            && Objects.equals(content, that.content)
            && Objects.equals(sourceId, that.sourceId)
            && Objects.equals(sourceName, that.sourceName)
            && Objects.equals(documentId, that.documentId)
            && Objects.equals(chunkIndex, that.chunkIndex)
            && Objects.equals(chunkCount, that.chunkCount)
            && Objects.equals(contentFingerprint, that.contentFingerprint)
            && Objects.equals(metadataDroppedCount, that.metadataDroppedCount)
            && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            content,
            sourceId,
            sourceName,
            documentId,
            chunkIndex,
            chunkCount,
            contentFingerprint,
            metadataDroppedCount,
            metadata
        );
    }
}
