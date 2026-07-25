package ai.fabric.annotation;

import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares text that may contribute to semantic search and/or RAG evidence.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AISearchable {

    /**
     * Stable logical field name. The Java member name is used when blank.
     */
    String name() default "";

    /**
     * Approved projection destinations.
     */
    AISearchDestination[] destinations() default {
        AISearchDestination.SEMANTIC_SEARCH,
        AISearchDestination.RAG_CONTEXT
    };

    /**
     * Preprocessing applied before projection.
     */
    AISearchPreprocessing preprocessing() default AISearchPreprocessing.NORMALIZE;

    /**
     * Maximum projected character length, or {@code -1} for no field limit.
     */
    int maxLength() default 5000;

    /**
     * Projection order and token-budget retention priority from 0 through 100.
     *
     * <p>This does not modify vector similarity scores.</p>
     */
    int priority() default 50;

    /**
     * Whether a missing or blank value must fail projection.
     */
    boolean required() default false;
}
