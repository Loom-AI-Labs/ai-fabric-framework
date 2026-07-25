package ai.fabric.annotation;

import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares structured entity context and its approved destinations.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AIContext {

    /**
     * Stable context key. The Java member name is used when blank.
     */
    String key() default "";

    /**
     * Validated type hint.
     */
    AIContextDataType dataType() default AIContextDataType.AUTO;

    /**
     * Optional date or number format.
     */
    String format() default "";

    /**
     * Approved context destinations.
     */
    AIContextDestination[] destinations() default {
        AIContextDestination.VECTOR_METADATA,
        AIContextDestination.LLM_CONTEXT,
        AIContextDestination.API_RESPONSE
    };

    /**
     * Bounded schema guidance included when this context is rendered for an LLM.
     */
    String description() default "";

    /**
     * Projection order and token-budget retention priority from 0 through 100.
     */
    int priority() default 50;

    /**
     * Whether a missing value must fail projection.
     */
    boolean required() default false;

    /**
     * Whether PII processing must succeed before the value enters any destination.
     */
    boolean sanitizePII() default false;
}
