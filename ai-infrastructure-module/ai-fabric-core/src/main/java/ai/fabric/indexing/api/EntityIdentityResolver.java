package ai.fabric.indexing.api;

/**
 * Application extension for stable entity identity.
 */
public interface EntityIdentityResolver {

    boolean supports(Class<?> entityClass);

    Object resolveIdentity(Object entity);

    default String source() {
        return getClass().getName();
    }
}
