package ai.fabric.intent.action.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Evaluates whether one invocation of a statically confirmable action requires
 * explicit confirmation.
 *
 * <p>The annotated method must return {@code boolean} or {@link Boolean}. It
 * may receive action parameters annotated with {@link Param} and framework
 * action-context parameters. Evaluation fails closed: an exception means that
 * confirmation is required.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionConfirmationRequired {
}
