package ai.fabric.indexing.document;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Validates document targets against AI Fabric entity indexing policy. */
public class DocumentEntityPolicyValidator {

    private final AIEntityConfigurationLoader configurationLoader;

    public DocumentEntityPolicyValidator(
        AIEntityConfigurationLoader configurationLoader
    ) {
        this.configurationLoader = Objects.requireNonNull(
            configurationLoader,
            "configurationLoader is required"
        );
    }

    public AIEntityConfig requireIndexable(String entityType, String tenantId) {
        AIEntityConfig config = configurationLoader.getEntityConfig(entityType);
        if (config == null) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED,
                "Unknown AI Fabric document entity type"
            );
        }
        if (config.getIndexing() == null
            || !Boolean.TRUE.equals(config.getIndexing().getEnabled())) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_INDEXING_FAILED,
                "AI Fabric document entity type is not indexable"
            );
        }
        if (requiresTenant(config) && (tenantId == null || tenantId.isBlank())) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_METADATA_REJECTED,
                "A tenant identifier is required for this document entity type"
            );
        }
        return config;
    }

    private boolean requiresTenant(AIEntityConfig config) {
        List<AIMetadataField> fields = config.getMetadataFields();
        if (fields == null) {
            return false;
        }
        return fields.stream().anyMatch(field -> field != null
            && Boolean.TRUE.equals(field.getRequired())
            && isTenantField(field.getName()));
    }

    private boolean isTenantField(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]", "");
        return "tenantid".equals(normalized)
            || "aidocumenttenantid".equals(normalized);
    }
}
