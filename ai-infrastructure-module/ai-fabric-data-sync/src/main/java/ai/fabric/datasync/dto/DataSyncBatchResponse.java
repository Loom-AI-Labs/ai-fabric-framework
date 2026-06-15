package ai.fabric.datasync.dto;

import java.util.List;

/**
 * Batch sync response with per-operation results.
 */
public class DataSyncBatchResponse {

    private Boolean success;

    private String errorCode;

    private String message;

    private String providerRequestId;

    private Integer totalOperations;

    private Integer succeededOperations;

    private Integer failedOperations;

    private List<DataSyncOperationResponse> results;

    public DataSyncBatchResponse() {
    }

    public DataSyncBatchResponse(Boolean success,
                                 String errorCode,
                                 String message,
                                 String providerRequestId,
                                 Integer totalOperations,
                                 Integer succeededOperations,
                                 Integer failedOperations,
                                 List<DataSyncOperationResponse> results) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.providerRequestId = providerRequestId;
        this.totalOperations = totalOperations;
        this.succeededOperations = succeededOperations;
        this.failedOperations = failedOperations;
        this.results = results;
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

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public Integer getTotalOperations() {
        return totalOperations;
    }

    public void setTotalOperations(Integer totalOperations) {
        this.totalOperations = totalOperations;
    }

    public Integer getSucceededOperations() {
        return succeededOperations;
    }

    public void setSucceededOperations(Integer succeededOperations) {
        this.succeededOperations = succeededOperations;
    }

    public Integer getFailedOperations() {
        return failedOperations;
    }

    public void setFailedOperations(Integer failedOperations) {
        this.failedOperations = failedOperations;
    }

    public List<DataSyncOperationResponse> getResults() {
        return results;
    }

    public void setResults(List<DataSyncOperationResponse> results) {
        this.results = results;
    }
}
