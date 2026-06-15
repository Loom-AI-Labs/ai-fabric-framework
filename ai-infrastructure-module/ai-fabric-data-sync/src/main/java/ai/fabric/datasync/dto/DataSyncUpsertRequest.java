package ai.fabric.datasync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Upsert request for syncing a record into a vector space.
 *
 * <p>Provide either {@link #content} (pre-normalized) OR {@link #entity} (raw fields to normalize).</p>
 */
public class DataSyncUpsertRequest {

    @NotBlank
    private String vectorSpace;

    @NotBlank
    private String id;

    /**
     * Pre-normalized text content to embed and index.
     */
    private String content;

    /**
     * Optional raw entity payload. When {@link #content} is blank, the server will normalize this using
     * {@code ai-entity-config.yml} for the corresponding vector space.
     */
    private Map<String, Object> entity;

    /**
     * Optional metadata to store alongside the vector.
     */
    private Map<String, Object> metadata;

    /**
     * Optional stable identity hints used for deterministic chunk ids and retry-safe idempotency.
     */
    @Valid
    private DataSyncIdentity identity;

    @Valid
    @NotNull
    private DataSyncTrace trace;

    public DataSyncUpsertRequest() {
    }

    public DataSyncUpsertRequest(String vectorSpace,
                                 String id,
                                 String content,
                                 Map<String, Object> entity,
                                 Map<String, Object> metadata,
                                 DataSyncIdentity identity,
                                 DataSyncTrace trace) {
        this.vectorSpace = vectorSpace;
        this.id = id;
        this.content = content;
        this.entity = entity;
        this.metadata = metadata;
        this.identity = identity;
        this.trace = trace;
    }

    public String getVectorSpace() {
        return vectorSpace;
    }

    public void setVectorSpace(String vectorSpace) {
        this.vectorSpace = vectorSpace;
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

    public Map<String, Object> getEntity() {
        return entity;
    }

    public void setEntity(Map<String, Object> entity) {
        this.entity = entity;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public DataSyncIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(DataSyncIdentity identity) {
        this.identity = identity;
    }

    public DataSyncTrace getTrace() {
        return trace;
    }

    public void setTrace(DataSyncTrace trace) {
        this.trace = trace;
    }
}
