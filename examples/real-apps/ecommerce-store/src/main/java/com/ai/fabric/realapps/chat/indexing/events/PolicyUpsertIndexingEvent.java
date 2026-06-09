package com.ai.fabric.realapps.chat.indexing.events;

public record PolicyUpsertIndexingEvent(
    long policyId,
    String title,
    String text,
    String classification
) {
}
