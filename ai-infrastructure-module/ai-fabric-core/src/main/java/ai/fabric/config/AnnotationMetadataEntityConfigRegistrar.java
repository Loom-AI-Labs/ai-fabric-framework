package ai.fabric.config;

import ai.fabric.annotation.AICapable;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;
import ai.fabric.processor.AnnotationFieldScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.List;
import java.util.Objects;

/**
 * Registers/merges annotation-driven @AIContext metadata schemas into YAML entity configs.
 *
 * <p>Uses JPA metamodel to discover entity classes, reads {@link AICapable#entityType()},
 * derives metadata fields via {@link AnnotationMetadataFieldGenerator}, and merges into
 * {@link AIEntityConfigurationLoader}. YAML remains authoritative on conflicts.</p>
 */
@Slf4j
public class AnnotationMetadataEntityConfigRegistrar implements SmartInitializingSingleton {

    private final EntityManagerFactory entityManagerFactory;
    private final AIEntityConfigurationLoader configurationLoader;
    private final AnnotationMetadataFieldGenerator fieldGenerator;
    private final boolean createMissingEntityConfig;

    public AnnotationMetadataEntityConfigRegistrar(EntityManagerFactory entityManagerFactory,
                                                   AIEntityConfigurationLoader configurationLoader,
                                                   AnnotationFieldScanner annotationFieldScanner,
                                                   boolean createMissingEntityConfig) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        this.fieldGenerator = new AnnotationMetadataFieldGenerator(
            Objects.requireNonNull(annotationFieldScanner, "annotationFieldScanner")
        );
        this.createMissingEntityConfig = createMissingEntityConfig;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            registerAllEntities();
        } catch (Exception ex) {
            log.warn("Failed to register annotation-driven metadata schemas (continuing without them): {}", ex.getMessage(), ex);
        }
    }

    private void registerAllEntities() {
        if (entityManagerFactory.getMetamodel() == null) {
            return;
        }

        for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
            if (entityType == null) {
                continue;
            }
            Class<?> javaType = entityType.getJavaType();
            if (javaType == null) {
                continue;
            }
            AICapable aiCapable = javaType.getAnnotation(AICapable.class);
            if (aiCapable == null || aiCapable.entityType() == null || aiCapable.entityType().isBlank()) {
                continue;
            }

            String typeKey = aiCapable.entityType().trim();
            List<AIMetadataField> metadataFields = fieldGenerator.buildMetadataFields(javaType);
            if (metadataFields.isEmpty()) {
                continue;
            }

            AIEntityConfig existing = configurationLoader.getEntityConfig(typeKey);
            if (existing == null && !createMissingEntityConfig) {
                log.debug("Skipping annotation metadata for entityType '{}' (no YAML config found)", typeKey);
                continue;
            }

            configurationLoader.mergeMetadataFields(typeKey, metadataFields, createMissingEntityConfig);
            log.debug("Merged {} @AIContext metadata fields into entityType '{}'", metadataFields.size(), typeKey);
        }
    }
}

