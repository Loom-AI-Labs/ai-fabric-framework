package ai.fabric.intent.action.confirmation;

import java.util.List;

/**
 * Provides declarative confirmation interception rules loaded from runtime configuration.
 */
public interface ConfirmationInterceptorCatalogProvider {

    List<ConfirmationInterceptorRule> getRules();

    default List<String> getSourceLocations() {
        return List.of();
    }
}
