package ai.fabric.chat.annotation;

import ai.fabric.dto.IntentType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Intercepts a user confirmation turn when a specific pending action is at the top of the confirmation stack.
 *
 * <p>Handlers are evaluated in ascending {@link #priority()} order (lower runs first).</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnPendingActionConfirmation {

    /**
     * Pending action names this interceptor applies to (case-insensitive).
     */
    String[] pendingActions();

    /**
     * Required confirmation intent type (must be {@link IntentType#CONFIRMATION_POSITIVE} or
     * {@link IntentType#CONFIRMATION_NEGATIVE}).
     */
    IntentType confirmation();

    /**
     * Lower runs earlier.
     */
    int priority() default 100;

    /**
     * When true, this interceptor only runs on single-intent confirmation turns (most common).
     */
    boolean requireSingleIntent() default true;

    /**
     * Optional guard stored on the pending action params.
     *
     * <p>If set, this interceptor only applies when {@code actionParams[onceParam] != true}.</p>
     * <p>When {@link #autoSetOnceParam()} is true, the framework will set this flag to {@code true}
     * on the top pending action before invoking the handler.</p>
     */
    String onceParam() default "";

    /**
     * If true and {@link #onceParam()} is set, the framework sets {@code onceParam=true} automatically.
     */
    boolean autoSetOnceParam() default true;
}

