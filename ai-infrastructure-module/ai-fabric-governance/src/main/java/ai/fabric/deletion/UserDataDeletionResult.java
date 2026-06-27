package ai.fabric.deletion;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class UserDataDeletionResult {

    public enum Status {
        COMPLETED,
        PARTIAL,
        SKIPPED
    }

    String userId;
    Status status;
    Integer behaviorsDeleted;
    Integer indexedEntitiesDeleted;
    Integer vectorsDeleted;
    Integer domainRecordsDeleted;
    Integer auditEntriesDeleted;
    Integer deletionFailures;
    List<String> failureMessages;
    LocalDateTime timestamp;
    String message;
}
