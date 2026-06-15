package ai.fabric.annotation;

import ai.fabric.indexing.api.IndexingStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AICapable Annotation
 * 
 * Entity-level annotation to enable AI capabilities for classes.
 * AI behavior is defined in the configuration file.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AICapable {
    
    /**
     * Entity type for AI processing
     * Used to lookup configuration in ai-entity-config.yml
     */
    String entityType() default "";
    
    /**
     * Configuration file path
     * Default: ai-entity-config.yml
     */
    String configFile() default "ai-entity-config.yml";
    
    /**
     * Enable automatic AI processing
     * Default: true
     */
    boolean autoProcess() default true;
    
    /**
     * AI features to enable
     * Options: embedding, search, rag, recommendation, validation, analysis
     */
    String[] features() default {"embedding", "search"};
    
    /**
     * Enable search capabilities
     * Default: true
     */
    boolean enableSearch() default true;
    
    /**
     * Enable recommendation capabilities
     * Default: false
     */
    boolean enableRecommendations() default false;
    
    /**
     * Enable automatic embedding generation
     * Default: true
     */
    boolean autoEmbedding() default true;
    
    /**
     * Enable indexing for search
     * Default: true
     */
    boolean indexable() default true;

    /**
     * Default indexing strategy for all operations.
     */
    IndexingStrategy indexingStrategy() default IndexingStrategy.ASYNC;

    /**
     * Override for create operations. Set to AUTO to inherit {@link #indexingStrategy()}.
     */
    IndexingStrategy onCreateStrategy() default IndexingStrategy.AUTO;

    /**
     * Override for update operations. Set to AUTO to inherit {@link #indexingStrategy()}.
     */
    IndexingStrategy onUpdateStrategy() default IndexingStrategy.AUTO;

    /**
     * Override for delete operations. Set to AUTO to inherit {@link #indexingStrategy()}.
     */
    IndexingStrategy onDeleteStrategy() default IndexingStrategy.AUTO;

    /**
     * JPA repository used by the migration module to backfill data.
     * Optional for existing users but strongly recommended to enable migration.
     */
    Class<? extends JpaRepository<?, ?>> migrationRepository() default NoMigrationRepository.class;
}
