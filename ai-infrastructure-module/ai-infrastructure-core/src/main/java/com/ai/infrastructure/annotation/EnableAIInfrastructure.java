package com.ai.infrastructure.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional marker annotation for AI Fabric integration.
 *
 * <p>AI Fabric uses standard Spring Boot auto-configuration (via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}),
 * so simply adding the relevant dependency is sufficient for bean wiring.</p>
 *
 * <p>This annotation is kept for documentation and discoverability, but it intentionally
 * does not import auto-configuration classes directly, to avoid interfering with Spring Boot’s
 * auto-configuration ordering (e.g., optional modules like indexing).</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableAIInfrastructure {}
