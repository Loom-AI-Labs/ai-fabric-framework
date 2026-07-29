package ai.fabric.execution.review.dispatch;

public interface ReviewTaskDispatcher {

    String id();

    ReviewDispatchResult dispatch(ReviewDispatchRequest request);
}
