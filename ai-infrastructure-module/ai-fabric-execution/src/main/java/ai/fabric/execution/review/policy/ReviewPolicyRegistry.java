package ai.fabric.execution.review.policy;

import java.util.List;
import java.util.Optional;

public interface ReviewPolicyRegistry {

    Optional<RegisteredReviewPolicy> find(ReviewPolicyId id);

    List<RegisteredReviewPolicy> list();

    default RegisteredReviewPolicy require(ReviewPolicyId id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(
            "Review policy is not registered: " + id
        ));
    }
}
