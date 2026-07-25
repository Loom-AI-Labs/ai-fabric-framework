package ai.fabric.indexing.model;

/**
 * Compiled entity-member accessor used during projection.
 */
public interface AIValueAccessor {

    String memberName();

    Class<?> valueType();

    Object read(Object target);
}
