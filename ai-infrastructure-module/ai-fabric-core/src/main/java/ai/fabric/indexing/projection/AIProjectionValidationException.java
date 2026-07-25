package ai.fabric.indexing.projection;

/**
 * Safe projection failure that never includes raw field content.
 */
public class AIProjectionValidationException extends IllegalStateException {

    private final String entityType;
    private final String entityId;
    private final String fieldName;
    private final String destination;
    private final String errorCode;

    public AIProjectionValidationException(
        String entityType,
        String entityId,
        String fieldName,
        String destination,
        String errorCode
    ) {
        super("%s for entityType=%s field=%s destination=%s"
            .formatted(errorCode, entityType, fieldName, destination));
        this.entityType = entityType;
        this.entityId = entityId;
        this.fieldName = fieldName;
        this.destination = destination;
        this.errorCode = errorCode;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getDestination() {
        return destination;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
