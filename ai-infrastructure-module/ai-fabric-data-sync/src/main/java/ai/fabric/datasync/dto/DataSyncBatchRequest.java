package ai.fabric.datasync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Batch sync request containing multiple operations.
 */
public class DataSyncBatchRequest {

    @Valid
    @NotNull
    private DataSyncTrace trace;

    @Valid
    @NotEmpty
    private List<DataSyncOperation> operations;

    public DataSyncBatchRequest() {
    }

    public DataSyncBatchRequest(DataSyncTrace trace, List<DataSyncOperation> operations) {
        this.trace = trace;
        this.operations = operations;
    }

    public DataSyncTrace getTrace() {
        return trace;
    }

    public void setTrace(DataSyncTrace trace) {
        this.trace = trace;
    }

    public List<DataSyncOperation> getOperations() {
        return operations;
    }

    public void setOperations(List<DataSyncOperation> operations) {
        this.operations = operations;
    }
}
