package com.ai.fabric.realapps.chat.indexing.events;

import java.math.BigDecimal;

public record ProductUpsertIndexingEvent(
    String sku,
    String name,
    String description,
    String category,
    String tags,
    BigDecimal price,
    String currency,
    Integer inStockQty
) {
}
