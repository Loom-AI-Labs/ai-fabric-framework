package com.ai.fabric.realapps.chat.migration.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal optional API-key guard for destructive admin endpoints.
 */
final class AdminAuth {

    private AdminAuth() {
    }

    static boolean isAuthorized(String configuredApiKey,
                                String configuredHeaderName,
                                HttpServletRequest request) {
        if (!StringUtils.hasText(configuredApiKey)) {
            return false;
        }
        String headerName = StringUtils.hasText(configuredHeaderName) ? configuredHeaderName : "X-AIFABRIC-API-KEY";
        String provided = request != null ? request.getHeader(headerName) : null;
        if (!StringUtils.hasText(provided)) {
            return false;
        }
        return constantTimeEquals(configuredApiKey.trim(), provided.trim());
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
