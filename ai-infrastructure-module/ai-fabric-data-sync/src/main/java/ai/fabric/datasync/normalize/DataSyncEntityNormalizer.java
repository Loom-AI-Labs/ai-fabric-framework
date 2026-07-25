package ai.fabric.datasync.normalize;

import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

/**
 * Applies Data Sync request bounds before building the canonical index document.
 */
public class DataSyncEntityNormalizer {

    private final AIDataSyncProperties properties;
    private final AIConfiguredEntityProjectionService projectionService;

    public DataSyncEntityNormalizer(
        AIDataSyncProperties properties,
        AIConfiguredEntityProjectionService projectionService
    ) {
        this.properties = Objects.requireNonNull(
            properties,
            "properties is required"
        );
        this.projectionService = Objects.requireNonNull(
            projectionService,
            "projectionService is required"
        );
    }

    public AIIndexDocument projectUpsert(
        AIEntityConfig config,
        String vectorSpace,
        String id,
        String content,
        Map<String, Object> entity,
        Map<String, Object> metadata,
        Map<String, Object> trustedVectorMetadata,
        Long sourceVersion,
        String correlationId
    ) {
        validateIdentity(config, vectorSpace, id);
        if (!StringUtils.hasText(content)
            && (entity == null || entity.isEmpty())) {
            throw new IllegalArgumentException(
                "Either content or entity must be provided for UPSERT operations."
            );
        }
        if (StringUtils.hasText(content)
            && properties.getMaxContentChars() > 0
            && content.trim().length() > properties.getMaxContentChars()) {
            throw new IllegalArgumentException(
                "Content exceeds maxContentChars="
                    + properties.getMaxContentChars()
            );
        }
        int metadataKeys = (metadata == null ? 0 : metadata.size())
            + (trustedVectorMetadata == null ? 0 : trustedVectorMetadata.size());
        if (properties.getMaxMetadataKeys() > 0
            && metadataKeys > properties.getMaxMetadataKeys()) {
            throw new IllegalArgumentException(
                "Metadata exceeds maxMetadataKeys="
                    + properties.getMaxMetadataKeys()
            );
        }

        AIIndexDocument document = projectionService.project(
            config,
            id,
            content,
            entity,
            metadata,
            trustedVectorMetadata,
            sourceVersion,
            correlationId,
            AIProcessOperation.UPDATE
        );
        if (properties.getMaxContentChars() > 0
            && (document.semanticSearchText().length()
                > properties.getMaxContentChars()
                || document.ragContextText().length()
                > properties.getMaxContentChars())) {
            throw new IllegalArgumentException(
                "Projected content exceeds maxContentChars="
                    + properties.getMaxContentChars()
            );
        }
        return document;
    }

    public AIIndexDocument projectDelete(
        AIEntityConfig config,
        String vectorSpace,
        String id,
        String correlationId
    ) {
        validateIdentity(config, vectorSpace, id);
        return projectionService.projectDelete(config, id, correlationId);
    }

    private void validateIdentity(
        AIEntityConfig config,
        String vectorSpace,
        String id
    ) {
        if (config == null) {
            throw new IllegalArgumentException("entity config is required");
        }
        if (!StringUtils.hasText(vectorSpace)
            || !vectorSpace.trim().equals(config.getEntityType())) {
            throw new IllegalArgumentException(
                "vectorSpace must match configured entityType"
            );
        }
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id is required");
        }
    }
}
