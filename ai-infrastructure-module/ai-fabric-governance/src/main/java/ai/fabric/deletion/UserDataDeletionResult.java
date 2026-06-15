package ai.fabric.deletion;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class UserDataDeletionResult {

    public enum Status {
        COMPLETED,
        SKIPPED
    }

    String userId;
    Status status;
    Integer behaviorsDeleted;
    Integer indexedEntitiesDeleted;
    Integer vectorsDeleted;
    Integer domainRecordsDeleted;
    Integer auditEntriesDeleted;
    LocalDateTime timestamp;
    String message;
}

