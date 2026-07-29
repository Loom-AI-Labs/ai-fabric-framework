package ai.fabric.execution.review.continuation;

public interface ReviewInformationHandler {

    String id();

    ReviewInformationRequestOutcome requestInformation(
        ReviewInformationRequestContext context
    );

    ReviewInformationSubmissionOutcome receiveInformation(
        ReviewInformationSubmissionContext context
    );
}
