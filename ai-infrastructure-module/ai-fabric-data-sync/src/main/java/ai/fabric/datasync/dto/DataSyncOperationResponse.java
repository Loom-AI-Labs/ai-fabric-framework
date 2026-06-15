package ai.fabric.datasync.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of a single data sync operation.
 */
public class DataSyncOperationResponse {

    private Boolean success;

    private String errorCode;

    private String message;

    private DataSyncOperationType type;

    private String vectorSpace;

    private String id;

    /**
     * Vector ID assigned by the vector database (UPSERT only).
     */
    private String vectorId;

    private Long processingTimeMs;

    private List<String> warnings;

    private Map<String, Object> metadata;

    public DataSyncOperationResponse() {
    }

    public DataSyncOperationResponse(Boolean success,
                                     String errorCode,
                                     String message,
                                     DataSyncOperationType type,
                                     String vectorSpace,
                                     String id,
                                     String vectorId,
                                     Long processingTimeMs,
                                     List<String> warnings,
                                     Map<String, Object> metadata) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.type = type;
        this.vectorSpace = vectorSpace;
        this.id = id;
        this.vectorId = vectorId;
        this.processingTimeMs = processingTimeMs;
        this.warnings = warnings;
        this.metadata = metadata;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public DataSyncOperationType getType() {
        return type;
    }

    public void setType(DataSyncOperationType type) {
        this.type = type;
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

    public String getVectorId() {
        return vectorId;
    }

    public void setVectorId(String vectorId) {
        this.vectorId = vectorId;
    }

    public Long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(Long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
