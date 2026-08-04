package com.ai.fabric.realapps.livesync.service;

import ai.fabric.indexing.api.IndexingOutcome;
import java.util.Objects;

record TrackedEntityMutation<T>(T entity, IndexingOutcome indexing) {

    TrackedEntityMutation {
        Objects.requireNonNull(entity, "entity is required");
        Objects.requireNonNull(indexing, "indexing is required");
    }
}
