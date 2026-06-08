package com.ai.infrastructure.intent.action.connector;

import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConnectorConfirmationInterceptorCatalogProvider implements ConfirmationInterceptorCatalogProvider {

    private final ConnectorActionCatalogService catalogService;

    @Override
    public List<ConfirmationInterceptorRule> getRules() {
        return catalogService.getCatalog().confirmationInterceptors();
    }

    @Override
    public List<String> getSourceLocations() {
        return catalogService.getCatalog().sourceLocations();
    }
}
