package ai.fabric.execution.review.persistence;

import java.util.List;
import java.util.Optional;

public interface ReviewDispatchRepository {

    ReviewDispatchRecord create(ReviewDispatchRecord dispatch);

    Optional<ReviewDispatchRecord> findById(String dispatchId);

    List<ReviewDispatchRecord> findByTaskId(String taskId);

    boolean compareAndSet(
        ReviewDispatchRecord expected,
        ReviewDispatchRecord updated
    );

    int deleteByTaskId(String taskId);

    final class DuplicateDispatchException extends RuntimeException {
        public DuplicateDispatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
