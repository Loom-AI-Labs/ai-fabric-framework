package com.ai.infrastructure.relay.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class HmacSigner {

    private HmacSigner() {
    }

    static String signBase64(String secret, String timestamp, String nonce, String body) {
        try {
            String message = timestamp + "\n" + nonce + "\n" + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC signature: " + ex.getMessage(), ex);
        }
    }
}

