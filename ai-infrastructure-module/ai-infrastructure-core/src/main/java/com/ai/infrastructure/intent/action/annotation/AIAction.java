package com.ai.infrastructure.intent.action.annotation;

import com.ai.infrastructure.intent.action.ActionAccessMode;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an AI action that can be selected by the intent-extraction layer and executed by the orchestrator.
 *
 * <p>This is a Spring stereotype: annotating a class with {@code @AIAction} registers it as a bean.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AIAction {

    /**
     * Stable action identifier (e.g. {@code create_ticket}).
     */
    String name();

    /**
     * Human-readable description used in prompts and API metadata.
     */
    String description();

    /**
     * Logical grouping (e.g. {@code support}, {@code operations}).
     */
    String category() default "general";

    /**
     * Side-effect semantics for this action.
     */
    ActionAccessMode accessMode();

    /**
     * Whether this action requires explicit user confirmation before execution.
     *
     * <p>Greenfield rule: must be specified explicitly per action (no global defaults/overrides).</p>
     */
    boolean requiresConfirmation();

    /**
     * Whether this action may execute for anonymous requests.
     *
     * <p>Defaults to {@code false}. Public/guest execution must be an explicit action-level opt-in.</p>
     */
    boolean anonymousAllowed() default false;

    /**
     * Whether this READ action is eligible for planner-driven read-action resolution.
     *
     * <p>Defaults to {@code false} so autonomous read planning stays fail-closed until
     * the action is explicitly reviewed and approved.</p>
     */
    boolean readActionResolutionEligible() default false;
}
