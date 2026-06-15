package ai.fabric.relay.store;

import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation intended for dev/single-instance deployments only.
 */
public final class InMemoryRelayKeyValueStore implements RelayKeyValueStore {

    private final String keyPrefix;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryRelayKeyValueStore(String keyPrefix) {
        this(keyPrefix, Clock.systemUTC());
    }

    public InMemoryRelayKeyValueStore(String keyPrefix, Clock clock) {
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "aifabric:relay:";
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            return false;
        }

        String fullKey = fullKey(key);
        long nowMillis = nowMillis();
        long expiresAtMillis = nowMillis + ttlMillis(ttl);

        while (true) {
            Entry existing = entries.get(fullKey);
            if (existing != null && existing.expiresAtMillis > nowMillis) {
                return false;
            }
            if (existing != null) {
                entries.remove(fullKey, existing);
                continue;
            }

            Entry created = new Entry(value, expiresAtMillis);
            Entry raced = entries.putIfAbsent(fullKey, created);
            if (raced == null) {
                cleanupIfNeeded(nowMillis);
                return true;
            }
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        long nowMillis = nowMillis();
        long expiresAtMillis = nowMillis + ttlMillis(ttl);
        entries.put(fullKey(key), new Entry(value, expiresAtMillis));
        cleanupIfNeeded(nowMillis);
    }

    @Override
    public String get(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }

        String fullKey = fullKey(key);
        long nowMillis = nowMillis();
        Entry entry = entries.get(fullKey);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis <= nowMillis) {
            entries.remove(fullKey, entry);
            return null;
        }
        return entry.value;
    }

    @Override
    public void delete(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        entries.remove(fullKey(key));
    }

    @Override
    public long increment(String key, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            return 0;
        }

        String fullKey = fullKey(key);
        long nowMillis = nowMillis();
        long ttlMillis = ttlMillis(ttl);
        AtomicLong out = new AtomicLong(0);

        entries.compute(fullKey, (k, existing) -> {
            if (existing == null || existing.expiresAtMillis <= nowMillis) {
                out.set(1);
                return new Entry("1", nowMillis + ttlMillis);
            }

            long current = parseLong(existing.value);
            long next = current + 1;
            out.set(next);
            return new Entry(String.valueOf(next), existing.expiresAtMillis);
        });

        cleanupIfNeeded(nowMillis);
        return out.get();
    }

    private String fullKey(String key) {
        return keyPrefix + key.trim();
    }

    private long nowMillis() {
        return Instant.now(clock).toEpochMilli();
    }

    private long ttlMillis(Duration ttl) {
        Duration safe = ttl != null ? ttl : Duration.ofSeconds(60);
        long millis = Math.max(1, safe.toMillis());
        return millis;
    }

    private long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private void cleanupIfNeeded(long nowMillis) {
        if (entries.size() < 20_000) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> entry = it.next();
            Entry value = entry.getValue();
            if (value == null || value.expiresAtMillis <= nowMillis) {
                it.remove();
            }
        }
    }

    private record Entry(String value, long expiresAtMillis) {
    }
}
