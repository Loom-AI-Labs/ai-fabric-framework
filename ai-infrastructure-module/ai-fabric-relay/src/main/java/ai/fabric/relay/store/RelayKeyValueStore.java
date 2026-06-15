package ai.fabric.relay.store;

import java.time.Duration;

/**
 * Minimal key/value + counter store used by the Relay for:
 * - nonce replay protection
 * - idempotency caching
 * - rate limiting counters
 *
 * <p>Implementations must apply TTL semantics and should be safe for concurrent access.</p>
 */
public interface RelayKeyValueStore {

    boolean putIfAbsent(String key, String value, Duration ttl);

    void put(String key, String value, Duration ttl);

    String get(String key);

    void delete(String key);

    long increment(String key, Duration ttl);
}

