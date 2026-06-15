package ai.fabric.datasync.dto;

import java.util.List;

/**
 * Response listing available vector spaces (configured entity types).
 */
public class DataSyncVectorSpacesResponse {

    private Boolean success;

    private String message;

    private List<String> vectorSpaces;

    public DataSyncVectorSpacesResponse() {
    }

    public DataSyncVectorSpacesResponse(Boolean success, String message, List<String> vectorSpaces) {
        this.success = success;
        this.message = message;
        this.vectorSpaces = vectorSpaces;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getVectorSpaces() {
        return vectorSpaces;
    }

    public void setVectorSpaces(List<String> vectorSpaces) {
        this.vectorSpaces = vectorSpaces;
    }
}
