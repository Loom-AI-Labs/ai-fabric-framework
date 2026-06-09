package com.ai.fabric.realapps.chat.indexing.events;

public record ReviewUpsertIndexingEvent(
    long reviewId,
    String userId,
    String sku,
    Integer rating,
    String text
) {
}
