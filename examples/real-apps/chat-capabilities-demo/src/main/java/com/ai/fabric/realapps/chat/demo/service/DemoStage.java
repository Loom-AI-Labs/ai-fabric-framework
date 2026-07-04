package com.ai.fabric.realapps.chat.demo.service;

import java.util.Locale;

public enum DemoStage {
    PRODUCTS,
    REVIEWS,
    POLICIES,
    COUPONS,
    TICKETS,
    FULL;

    public static DemoStage fromPath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("stage is required");
        }
        return DemoStage.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
