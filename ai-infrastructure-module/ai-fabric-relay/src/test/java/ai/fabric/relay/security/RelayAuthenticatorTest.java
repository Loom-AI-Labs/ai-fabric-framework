package ai.fabric.relay.security;

import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import ai.fabric.relay.store.InMemoryRelayKeyValueStore;
import ai.fabric.relay.store.RelayKeyValueStore;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayAuthenticatorTest {

    @Test
    void verify_acceptsValidHmacSignature() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        RelayKeyValueStore kv = new InMemoryRelayKeyValueStore("test:", clock);
        NonceStore nonceStore = new NonceStore(kv);
        RelayAuthenticator authenticator = new RelayAuthenticator(hmacProps("secret"), nonceStore, clock);

        String body = "{\"actionId\":\"ping\",\"params\":{},\"trace\":{}}";
        String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
        String nonce = "n1";
        String signature = sign("secret", timestamp, nonce, body);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-AIFABRIC-TIMESTAMP", timestamp);
        headers.put("X-AIFABRIC-NONCE", nonce);
        headers.put("X-AIFABRIC-SIGNATURE", signature);

        authenticator.verify(headers, body);
    }

    @Test
    void verify_rejectsInvalidSignature() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        RelayKeyValueStore kv = new InMemoryRelayKeyValueStore("test:", clock);
        NonceStore nonceStore = new NonceStore(kv);
        RelayAuthenticator authenticator = new RelayAuthenticator(hmacProps("secret"), nonceStore, clock);

        String body = "{\"actionId\":\"ping\",\"params\":{},\"trace\":{}}";
        String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
        String nonce = "n1";

        Map<String, String> headers = Map.of(
            "X-AIFABRIC-TIMESTAMP", timestamp,
            "X-AIFABRIC-NONCE", nonce,
            "X-AIFABRIC-SIGNATURE", "bad"
        );

        assertThatThrownBy(() -> authenticator.verify(headers, body))
            .isInstanceOf(RelayRequestRejectedException.class)
            .satisfies(ex -> {
                RelayRequestRejectedException rejected = (RelayRequestRejectedException) ex;
                org.assertj.core.api.Assertions.assertThat(rejected.getStatus().value()).isEqualTo(401);
                org.assertj.core.api.Assertions.assertThat(rejected.getErrorCode()).isEqualTo("UNAUTHORIZED");
            });
    }

    @Test
    void verify_doesNotConsumeNonceWhenSignatureIsInvalid() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        RelayKeyValueStore kv = new InMemoryRelayKeyValueStore("test:", clock);
        NonceStore nonceStore = new NonceStore(kv);
        RelayAuthenticator authenticator = new RelayAuthenticator(hmacProps("secret"), nonceStore, clock);

        String body = "{\"actionId\":\"ping\",\"params\":{},\"trace\":{}}";
        String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
        String nonce = "n1";

        Map<String, String> invalidHeaders = Map.of(
            "X-AIFABRIC-TIMESTAMP", timestamp,
            "X-AIFABRIC-NONCE", nonce,
            "X-AIFABRIC-SIGNATURE", "bad"
        );

        assertThatThrownBy(() -> authenticator.verify(invalidHeaders, body))
            .isInstanceOf(RelayRequestRejectedException.class);

        Map<String, String> validHeaders = new LinkedHashMap<>();
        validHeaders.put("X-AIFABRIC-TIMESTAMP", timestamp);
        validHeaders.put("X-AIFABRIC-NONCE", nonce);
        validHeaders.put("X-AIFABRIC-SIGNATURE", sign("secret", timestamp, nonce, body));

        authenticator.verify(validHeaders, body);
    }

    @Test
    void verify_rejectsNonceReplay() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        RelayKeyValueStore kv = new InMemoryRelayKeyValueStore("test:", clock);
        NonceStore nonceStore = new NonceStore(kv);
        RelayAuthenticator authenticator = new RelayAuthenticator(hmacProps("secret"), nonceStore, clock);

        String body = "{\"actionId\":\"ping\",\"params\":{},\"trace\":{}}";
        String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
        String nonce = "n1";
        String signature = sign("secret", timestamp, nonce, body);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-AIFABRIC-TIMESTAMP", timestamp);
        headers.put("X-AIFABRIC-NONCE", nonce);
        headers.put("X-AIFABRIC-SIGNATURE", signature);

        authenticator.verify(headers, body);

        assertThatThrownBy(() -> authenticator.verify(headers, body))
            .isInstanceOf(RelayRequestRejectedException.class)
            .satisfies(ex -> {
                RelayRequestRejectedException rejected = (RelayRequestRejectedException) ex;
                org.assertj.core.api.Assertions.assertThat(rejected.getStatus().value()).isEqualTo(401);
                org.assertj.core.api.Assertions.assertThat(rejected.getErrorCode()).isEqualTo("UNAUTHORIZED");
            });
    }

    private RelayProperties hmacProps(String secret) {
        RelayProperties props = new RelayProperties();
        props.getAuth().getHmac().setEnabled(true);
        props.getAuth().getHmac().setSecret(secret);
        props.getAuth().getHmac().setMaxClockSkewSeconds(300);
        props.getAuth().getHmac().setNonceTtlSeconds(600);
        return props;
    }

    private String sign(String secret, String timestamp, String nonce, String body) throws Exception {
        String message = timestamp + "\n" + nonce + "\n" + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig);
    }
}
