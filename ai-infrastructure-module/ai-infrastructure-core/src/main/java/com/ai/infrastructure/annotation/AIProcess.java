package com.ai.infrastructure.annotation;

import com.ai.infrastructure.indexing.api.IndexingStrategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AIProcess Annotation
 * 
 * Method-level annotation for automatic AI processing.
 * Contains both entity type and processing configuration.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AIProcess {
    
    /**
     * Entity type for AI processing
     * Used to lookup configuration in ai-entity-config.yml
     */
    String entityType() default "";
    
    /**
     * AI processing type
     * Options: create, update, delete, search, analyze
     */
    String processType() default "create";
    
    /**
     * Enable embedding generation
     * Default: true
     */
    boolean generateEmbedding() default true;
    
    /**
     * Enable search indexing
     * Default: true
     */
    boolean indexForSearch() default true;
    
    /**
     * Enable AI analysis
     * Default: false
     */
    boolean enableAnalysis() default false;

    /**
     * Optional indexing strategy override for this method.
     * Defaults to AUTO which inherits the resolved entity operation strategy.
     */
    IndexingStrategy indexingStrategy() default IndexingStrategy.AUTO;
}
