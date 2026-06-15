package ai.fabric.datasync.dto;

/**
 * Stable identity hints for idempotent bulk vectorization writes.
 *
 * <p>The caller still provides {@code id} as the logical target entity id. When {@code chunkId} is present,
 * the runtime derives a deterministic effective target id from that logical id plus the chunk identity.</p>
 */
public class DataSyncIdentity {

    private String sourceRecordId;

    private String sourceRecordVersion;

    private String chunkId;

    private Integer chunkCount;

    private String contentFingerprint;

    public DataSyncIdentity() {
    }

    public DataSyncIdentity(String sourceRecordId,
                            String sourceRecordVersion,
                            String chunkId,
                            Integer chunkCount,
                            String contentFingerprint) {
        this.sourceRecordId = sourceRecordId;
        this.sourceRecordVersion = sourceRecordVersion;
        this.chunkId = chunkId;
        this.chunkCount = chunkCount;
        this.contentFingerprint = contentFingerprint;
    }

    public String getSourceRecordId() {
        return sourceRecordId;
    }

    public void setSourceRecordId(String sourceRecordId) {
        this.sourceRecordId = sourceRecordId;
    }

    public String getSourceRecordVersion() {
        return sourceRecordVersion;
    }

    public void setSourceRecordVersion(String sourceRecordVersion) {
        this.sourceRecordVersion = sourceRecordVersion;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
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
}
