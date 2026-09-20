package ai.fabric.indexing.document;

import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;

import java.util.List;

/** Queue failure that retains work ids accepted before the failure. */
public class DocumentQueueSubmissionException extends DocumentIngestionException {

    private final List<String> acceptedWorkIds;

    public DocumentQueueSubmissionException(
        DocumentIngestionFailureCode code,
        String safeMessage,
        List<String> acceptedWorkIds,
        Throwable cause
    ) {
        super(code, safeMessage, cause);
        this.acceptedWorkIds = acceptedWorkIds == null
            ? List.of()
            : List.copyOf(acceptedWorkIds);
    }

    public List<String> getAcceptedWorkIds() {
        return acceptedWorkIds;
    }
}
