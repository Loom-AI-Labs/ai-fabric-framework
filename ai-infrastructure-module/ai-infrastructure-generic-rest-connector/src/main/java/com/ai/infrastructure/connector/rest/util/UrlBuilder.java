package com.ai.infrastructure.connector.rest.util;

import org.springframework.util.StringUtils;

public final class UrlBuilder {

    private UrlBuilder() {
    }

    public static String join(String baseUrl, String path) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String p = StringUtils.hasText(path) ? path.trim() : "";
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return base + p;
    }
}

