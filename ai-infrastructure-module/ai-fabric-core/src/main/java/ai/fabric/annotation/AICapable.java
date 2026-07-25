package ai.fabric.annotation;

import ai.fabric.indexing.api.IndexingStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a domain type that AI Fabric may inspect.
 *
 * <p>The annotation defines stable entity identity and lifecycle strategy only.
 * Searchable content and approved context are declared on fields. Runtime policy
 * may disable indexing, but cannot widen a field's declared destinations.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AICapable {

    /**
     * Stable entity type and vector-space identity.
     */
    String entityType();

    /**
     * Default indexing strategy for lifecycle operations.
     */
    IndexingStrategy indexingStrategy() default IndexingStrategy.ASYNC;

    /**
     * Create-operation override, or {@link IndexingStrategy#AUTO} to inherit.
     */
    IndexingStrategy onCreateStrategy() default IndexingStrategy.AUTO;

    /**
     * Update-operation override, or {@link IndexingStrategy#AUTO} to inherit.
     */
    IndexingStrategy onUpdateStrategy() default IndexingStrategy.AUTO;

    /**
     * Delete-operation override, or {@link IndexingStrategy#AUTO} to inherit.
     */
    IndexingStrategy onDeleteStrategy() default IndexingStrategy.AUTO;

    /**
     * Repository used by migration/backfill. Optional for non-JPA entities.
     */
    Class<? extends JpaRepository<?, ?>> migrationRepository() default NoMigrationRepository.class;
}
