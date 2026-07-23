package com.ai.fabric.realapps.livesync.service;

import java.util.Locale;
import org.springframework.util.StringUtils;

final class SyncEntitySupport {

    private SyncEntitySupport() {
    }

    static String entityId(String workspaceId, String recordKey) {
        return requireText(workspaceId, "workspaceId") + ":" + requireRecordKey(recordKey);
    }

    static String requireRecordKey(String value) {
        String normalized = requireText(value, "recordKey").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9-]{1,78}")) {
            throw new IllegalArgumentException("recordKey must contain 2-79 lowercase letters, numbers, or hyphens");
        }
        return normalized;
    }

    static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
