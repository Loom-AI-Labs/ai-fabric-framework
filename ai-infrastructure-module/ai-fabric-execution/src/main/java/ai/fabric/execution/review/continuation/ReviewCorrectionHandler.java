package ai.fabric.execution.review.continuation;

public interface ReviewCorrectionHandler {

    String id();

    ReviewCorrectionOutcome correct(ReviewCorrectionContext context);
}
