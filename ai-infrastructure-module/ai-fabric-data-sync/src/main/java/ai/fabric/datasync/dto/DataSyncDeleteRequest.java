package ai.fabric.datasync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Delete request for removing a record from a vector space.
 */
public class DataSyncDeleteRequest {

    @NotBlank
    private String vectorSpace;

    @NotBlank
    private String id;

    @Valid
    private DataSyncIdentity identity;

    @Valid
    @NotNull
    private DataSyncTrace trace;

    public DataSyncDeleteRequest() {
    }

    public DataSyncDeleteRequest(String vectorSpace, String id, DataSyncIdentity identity, DataSyncTrace trace) {
        this.vectorSpace = vectorSpace;
        this.id = id;
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
