package ai.fabric.intent.action.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional method that decides whether the current user may perform an action.
 *
 * <p>If present, the orchestrator will call it before prompting for confirmation or executing the action.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionAllowed {
}

