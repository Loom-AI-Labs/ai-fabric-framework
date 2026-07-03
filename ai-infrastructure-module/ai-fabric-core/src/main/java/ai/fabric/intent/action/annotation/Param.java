package ai.fabric.intent.action.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an action parameter extracted by the intent extractor.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * Parameter name. When omitted, the Java parameter name is used (requires {@code -parameters}).
     */
    String value() default "";

    /**
     * Whether this parameter must be present to execute safely.
     */
    boolean required() default false;

    /**
     * Human-readable description used in prompt-visible metadata.
     */
    String description() default "";

    /**
     * Optional regex pattern validation (applied to the string form of the value).
     */
    String pattern() default "";

    /**
     * Optional allowed values (case-insensitive string comparison).
     */
    String[] allowedValues() default {};

    /**
     * Optional numeric lower bound (only for numeric target types).
     */
    long min() default Long.MIN_VALUE;

    /**
     * Optional numeric upper bound (only for numeric target types).
     */
    long max() default Long.MAX_VALUE;

    /**
     * When true, this parameter can be populated by batching pinned targets.
     *
     * <p>Example: a batch action may accept {@code items: [{id, quantity}, ...]} derived from
     * multiple UI-selected pinned targets.</p>
     */
    boolean batchTargets() default false;

    /**
     * Whether a missing value is appropriate to ask the end user for.
     *
     * <p>Set to false for parameters that should come from trusted application context,
     * owned resources, server defaults, or internal resolution rather than a public form.</p>
     */
    boolean askUser() default true;

    /**
     * Optional prompt/user visibility for this parameter.
     *
     * <p>Supported values include INTERNAL, SYSTEM, and SECRET. Unknown or blank values are
     * treated as public for backward compatibility.</p>
     */
    String visibility() default "";
}
