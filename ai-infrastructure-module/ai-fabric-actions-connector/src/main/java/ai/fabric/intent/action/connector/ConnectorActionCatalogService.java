package ai.fabric.intent.action.connector;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConnectorActionCatalogService {

    private final AIActionCatalogProperties catalogProperties;
    private final ConnectorActionCatalogLoader catalogLoader;

    private volatile ConnectorActionCatalog cachedCatalog;

    public ConnectorActionCatalog getCatalog() {
        ConnectorActionCatalog current = cachedCatalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedCatalog == null) {
                List<AIActionCatalogProperties.ActionSourceProperties> sources =
                    catalogProperties != null ? catalogProperties.getSources() : List.of();
                cachedCatalog = catalogLoader != null
                    ? catalogLoader.loadCatalog(sources)
                    : new ConnectorActionCatalog(List.of(), List.of(), List.of(), List.of());
            }
            return cachedCatalog;
        }
    }
}
