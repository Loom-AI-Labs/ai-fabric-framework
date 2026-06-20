package ai.fabric.util;

import ai.fabric.exception.AIServiceException;

import java.util.List;

/**
 * Shared direct-provider validation for vector lifecycle calls.
 */
public final class VectorRecordInputValidation {

    private VectorRecordInputValidation() {
    }

    public static void requireStoreInputs(String provider, String entityType, String entityId, List<Double> embedding) {
        requireEntityIdentity(provider, entityType, entityId);
        requireEmbedding(provider, "storeVector", embedding);
    }

    public static void requireEntityIdentity(String provider, String entityType, String entityId) {
        requireText(provider, "entityType", entityType);
        requireText(provider, "entityId", entityId);
    }

    public static boolean hasEntityIdentity(String entityType, String entityId) {
        return hasText(entityType) && hasText(entityId);
    }

    public static boolean hasVectorId(String vectorId) {
        return hasText(vectorId);
    }

    public static void requireEmbedding(String provider, String operation, List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new AIServiceException(provider + " " + operation + " requires a non-empty embedding vector");
        }
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void requireText(String provider, String field, String value) {
        if (!hasText(value)) {
            throw new AIServiceException(provider + " " + field + " must not be blank");
        }
    }
}
