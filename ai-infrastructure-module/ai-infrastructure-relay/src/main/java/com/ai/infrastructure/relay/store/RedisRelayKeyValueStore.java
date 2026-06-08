package com.ai.infrastructure.relay.store;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;

public final class RedisRelayKeyValueStore implements RelayKeyValueStore, AutoCloseable {

    private final String keyPrefix;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public RedisRelayKeyValueStore(String keyPrefix, RedisURI redisUri) {
        this(keyPrefix, RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")));
    }

    public RedisRelayKeyValueStore(String keyPrefix, RedisClient client) {
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "aifabric:relay:";
        this.client = Objects.requireNonNull(client, "client");
        this.connection = this.client.connect();
        this.commands = this.connection.sync();
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        int ttlSeconds = ttlSeconds(ttl);
        String result = commands.set(fullKey(key), value, SetArgs.Builder.nx().ex(ttlSeconds));
        return "OK".equalsIgnoreCase(result);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        int ttlSeconds = ttlSeconds(ttl);
        commands.set(fullKey(key), value, SetArgs.Builder.ex(ttlSeconds));
    }

    @Override
    public String get(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return commands.get(fullKey(key));
    }

    @Override
    public void delete(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        commands.del(fullKey(key));
    }

    @Override
    public long increment(String key, Duration ttl) {
        if (!StringUtils.hasText(key)) {
            return 0;
        }

        int ttlSeconds = ttlSeconds(ttl);
        String fullKey = fullKey(key);

        commands.set(fullKey, "0", SetArgs.Builder.nx().ex(ttlSeconds));
        long next = commands.incr(fullKey);
        if (next == 1) {
            commands.expire(fullKey, ttlSeconds);
        }
        return next;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } finally {
            client.shutdown();
        }
    }

    private String fullKey(String key) {
        return keyPrefix + key.trim();
    }

    private int ttlSeconds(Duration ttl) {
        Duration safe = ttl != null ? ttl : Duration.ofSeconds(60);
        long seconds = safe.getSeconds();
        if (seconds <= 0) {
            seconds = 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}

