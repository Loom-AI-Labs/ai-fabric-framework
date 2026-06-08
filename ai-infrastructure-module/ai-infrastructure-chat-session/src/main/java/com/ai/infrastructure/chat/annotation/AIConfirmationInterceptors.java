package com.ai.infrastructure.chat.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Spring bean that contains one or more confirmation interception handlers.
 *
 * <p>Methods on this bean may be annotated with {@link OnPendingActionConfirmation} to intercept
 * confirmation flows (e.g., retention offers) without implementing {@code IntentResolver} directly.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AIConfirmationInterceptors {
}

