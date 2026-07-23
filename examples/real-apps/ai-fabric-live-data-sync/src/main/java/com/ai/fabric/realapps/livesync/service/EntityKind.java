package com.ai.fabric.realapps.livesync.service;

import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import java.util.Arrays;

public enum EntityKind {
    PRODUCT("products", SyncProduct.ENTITY_TYPE, "Product"),
    POLICY("policies", SyncPolicy.ENTITY_TYPE, "Policy"),
    GUIDE("guides", SyncGuide.ENTITY_TYPE, "Support guide");

    private final String path;
    private final String entityType;
    private final String label;

    EntityKind(String path, String entityType, String label) {
        this.path = path;
        this.entityType = entityType;
        this.label = label;
    }

    public String path() {
        return path;
    }

    public String entityType() {
        return entityType;
    }

    public String label() {
        return label;
    }

    public static EntityKind fromPath(String value) {
        return Arrays.stream(values())
            .filter(kind -> kind.path.equalsIgnoreCase(value) || kind.name().equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported entity type: " + value));
    }
}
