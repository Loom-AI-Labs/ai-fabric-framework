package com.ai.fabric.realapps.chat.indexing.listener;

import com.ai.fabric.realapps.chat.indexing.client.RestConnectorDataSyncClient;
import com.ai.fabric.realapps.chat.indexing.events.ProductDeleteIndexingEvent;
import com.ai.fabric.realapps.chat.indexing.events.ProductUpsertIndexingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connector.indexing", name = "enabled", havingValue = "true")
public class ProductIndexingListener {

    private final RestConnectorDataSyncClient dataSyncClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductUpsert(ProductUpsertIndexingEvent event) {
        if (event == null) {
            return;
        }
        try {
            dataSyncClient.upsertProduct(toEntity(event));
        } catch (Exception ex) {
            log.warn("Product upsert indexing failed for sku={}: {}", event.sku(), ex.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductDelete(ProductDeleteIndexingEvent event) {
        if (event == null) {
            return;
        }
        try {
            dataSyncClient.deleteProductBySku(event.sku());
        } catch (Exception ex) {
            log.warn("Product delete indexing failed for sku={}: {}", event.sku(), ex.getMessage());
        }
    }

    private Map<String, Object> toEntity(ProductUpsertIndexingEvent event) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("sku", event.sku());
        entity.put("name", event.name());
        entity.put("description", event.description());
        entity.put("category", event.category());
        entity.put("tags", event.tags());
        if (event.price() != null) {
            entity.put("price", event.price());
        }
        entity.put("currency", event.currency());
        if (event.inStockQty() != null) {
            entity.put("inStockQty", event.inStockQty());
        }
        return entity;
    }
}
