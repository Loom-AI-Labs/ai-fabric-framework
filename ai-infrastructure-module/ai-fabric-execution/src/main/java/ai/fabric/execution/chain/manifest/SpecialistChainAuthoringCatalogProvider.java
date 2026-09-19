package ai.fabric.execution.chain.manifest;

/** Trusted authoring view; never an authorization or model-discovery API. */
@FunctionalInterface
public interface SpecialistChainAuthoringCatalogProvider {

    SpecialistChainAuthoringCatalog catalog();
}
