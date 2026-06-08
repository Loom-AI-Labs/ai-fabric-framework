package com.ai.infrastructure.relay.security;

import com.ai.infrastructure.relay.store.RelayKeyValueStore;
import com.ai.infrastructure.relay.util.Hashing;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
public class NonceStore {

    private final RelayKeyValueStore store;

    public NonceStore(RelayKeyValueStore store) {
        this.store = store;
    }

    public boolean tryUse(String nonce, int ttlSeconds) {
        if (!StringUtils.hasText(nonce)) {
            return false;
        }

        String normalized = nonce.trim();
        int ttl = Math.max(1, ttlSeconds);
        String key = "nonce:" + Hashing.sha256Hex(normalized);

        try {
            return store.putIfAbsent(key, "1", Duration.ofSeconds(ttl));
        } catch (Exception ex) {
            // Fail closed: if we can't enforce nonce uniqueness, reject the request.
            return false;
        }
    }
}

