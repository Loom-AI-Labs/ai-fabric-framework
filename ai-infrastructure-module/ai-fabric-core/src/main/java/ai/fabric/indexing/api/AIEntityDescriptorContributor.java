package ai.fabric.indexing.api;

import java.util.Collection;

/**
 * Explicitly contributes non-JPA AI-capable entity classes for startup validation.
 */
@FunctionalInterface
public interface AIEntityDescriptorContributor {

    Collection<Class<?>> entityClasses();
}
