package ai.fabric.retention;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class RetentionCleanupResult {

    public enum Status {
        COMPLETED,
        PARTIAL,
        SKIPPED,
        DISABLED
    }

    Status status;
    Integer entityTypesScanned;
    Integer entriesEvaluated;
    Integer entriesEligible;
    Integer vectorsDeleted;
    Integer cleanupFailures;
    List<String> failureMessages;
    LocalDateTime timestamp;
    String message;
}
