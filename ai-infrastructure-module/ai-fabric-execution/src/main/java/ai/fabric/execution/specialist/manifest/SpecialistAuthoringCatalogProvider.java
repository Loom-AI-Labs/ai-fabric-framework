package ai.fabric.execution.specialist.manifest;

/**
 * Supplies trusted platform code with the bounded deployment inventory from
 * which a specialist manifest may request capabilities.
 */
@FunctionalInterface
public interface SpecialistAuthoringCatalogProvider {

    SpecialistAuthoringCatalog catalog();
}
