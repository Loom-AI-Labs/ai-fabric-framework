package com.ai.infrastructure.connector.rest.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((input != null ? input : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute SHA-256 hash: " + ex.getMessage(), ex);
        }
    }
}

