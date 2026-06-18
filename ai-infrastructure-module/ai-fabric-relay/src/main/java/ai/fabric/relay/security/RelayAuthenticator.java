package ai.fabric.relay.security;

import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@Service
public class RelayAuthenticator {

    private static final String ERROR_UNAUTHORIZED = "UNAUTHORIZED";

    private final RelayProperties properties;
    private final Clock clock;
    private final NonceStore nonceStore;

    @Autowired
    public RelayAuthenticator(RelayProperties properties, NonceStore nonceStore) {
        this(properties, nonceStore, Clock.systemUTC());
    }

    RelayAuthenticator(RelayProperties properties, NonceStore nonceStore, Clock clock) {
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.nonceStore = nonceStore;
    }

    public void verify(Map<String, String> headers, String body) {
        RelayProperties.Auth auth = properties != null ? properties.getAuth() : null;
        RelayProperties.ApiKey apiKey = auth != null ? auth.getApiKey() : null;
        RelayProperties.Hmac hmac = auth != null ? auth.getHmac() : null;

        if (apiKey != null && apiKey.isEnabled()) {
            String headerName = StringUtils.hasText(apiKey.getHeader()) ? apiKey.getHeader().trim() : "X-AIFABRIC-API-KEY";
            String provided = headerValue(headers, headerName);
            if (!StringUtils.hasText(provided) || !constantTimeEquals(apiKey.getValue(), provided)) {
                throw new RelayRequestRejectedException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHORIZED, "API key authentication failed.");
            }
        }

        if (hmac != null && hmac.isEnabled()) {
            if (!StringUtils.hasText(hmac.getSecret())) {
                throw new IllegalStateException("relay.auth.hmac.enabled=true but relay.auth.hmac.secret is missing.");
            }

            String timestamp = headerValue(headers, hmac.getTimestampHeader());
            String nonce = headerValue(headers, hmac.getNonceHeader());
            String signature = headerValue(headers, hmac.getSignatureHeader());
            if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
                throw new RelayRequestRejectedException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHORIZED, "HMAC authentication failed.");
            }

            long ts;
            try {
                ts = Long.parseLong(timestamp.trim());
            } catch (NumberFormatException ex) {
                throw new RelayRequestRejectedException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHORIZED, "HMAC authentication failed.");
            }

            long now = Instant.now(clock).getEpochSecond();
            long skew = Math.abs(now - ts);
            if (skew > hmac.getMaxClockSkewSeconds()) {
                throw new RelayRequestRejectedException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHORIZED, "HMAC authentication failed.");
            }

            String expected = HmacSigner.signBase64(hmac.getSecret(), timestamp.trim(), nonce.trim(), body);
            if (!constantTimeEquals(expected, signature.trim())) {
                throw new RelayRequestRejectedException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHORIZED, "HMAC authentication failed.");
            }

            if (!nonceStore.tryUse(nonce.trim(), hmac.getNonceTtlSeconds())) {
                throw new RelayRequestRejectedException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHORIZED, "HMAC authentication failed.");
            }
        }
    }

    private String headerValue(Map<String, String> headers, String headerName) {
        if (headers == null || !StringUtils.hasText(headerName)) {
            return null;
        }
        String target = headerName.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        // Avoid early-return on length mismatch to reduce timing side-channels.
        int result = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            byte other = i < b.length ? b[i] : 0;
            result |= a[i] ^ other;
        }
        return result == 0;
    }
}
