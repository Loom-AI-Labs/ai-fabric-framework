package ai.fabric.annotation;

import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AIProcessTargetResolver;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.api.NoAIProcessTargetResolver;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public Spring service method as an entity lifecycle boundary.
 *
 * <p>The operation is explicit and never inferred from the Java method name.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AIProcess {

    /**
     * Lifecycle operation performed by the annotated method.
     */
    AIProcessOperation operation();

    /**
     * Optional entity-type assertion. When present it must match the resolved target.
     */
    String entityType() default "";

    /**
     * Optional application target resolver for wrappers, argument-owned targets, or void deletes.
     */
    Class<? extends AIProcessTargetResolver> targetResolver() default NoAIProcessTargetResolver.class;

    /**
     * Optional strategy override. {@link IndexingStrategy#AUTO} inherits from the entity.
     */
    IndexingStrategy indexingStrategy() default IndexingStrategy.AUTO;
}
