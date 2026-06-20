package ai.fabric.intent.action.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional method that returns a bounded, explicitly shaped facts payload safe to send to an LLM after an action executes.
 *
 * <p>The method may return:</p>
 * <ul>
 *   <li>{@code java.util.Map<String,Object>}</li>
 *   <li>{@code java.util.Optional<java.util.Map<String,Object>>}</li>
 * </ul>
 *
 * <p>The method may declare no parameters, or exactly
 * {@code (ai.fabric.intent.action.ActionResult, ai.fabric.intent.action.ActionContext)}.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionFacts {
}
