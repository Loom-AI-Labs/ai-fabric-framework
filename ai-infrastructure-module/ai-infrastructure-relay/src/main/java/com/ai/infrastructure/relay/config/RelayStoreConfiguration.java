package com.ai.infrastructure.relay.config;

import com.ai.infrastructure.relay.store.InMemoryRelayKeyValueStore;
import com.ai.infrastructure.relay.store.RedisRelayKeyValueStore;
import com.ai.infrastructure.relay.store.RelayKeyValueStore;
import io.lettuce.core.RedisURI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RelayStoreConfiguration {

    @Bean
    public RelayKeyValueStore relayKeyValueStore(RelayProperties properties) {
        RelayProperties.Store store = properties != null ? properties.getStore() : null;
        RelayProperties.Store.Backend backend = store != null ? store.getBackend() : RelayProperties.Store.Backend.IN_MEMORY;

        String keyPrefix = store != null && StringUtils.hasText(store.getKeyPrefix())
            ? store.getKeyPrefix().trim()
            : "aifabric:relay:";

        if (backend == RelayProperties.Store.Backend.REDIS) {
            RedisURI uri = buildRedisUri(store != null ? store.getRedis() : null);
            return new RedisRelayKeyValueStore(keyPrefix, uri);
        }

        return new InMemoryRelayKeyValueStore(keyPrefix, Clock.systemUTC());
    }

    private RedisURI buildRedisUri(RelayProperties.Redis redis) {
        if (redis != null && StringUtils.hasText(redis.getUri())) {
            RedisURI uri = RedisURI.create(redis.getUri().trim());
            if (redis.getConnectTimeout() != null) {
                uri.setTimeout(redis.getConnectTimeout());
            }
            return uri;
        }

        String host = redis != null && StringUtils.hasText(redis.getHost()) ? redis.getHost().trim() : "localhost";
        int port = redis != null ? redis.getPort() : 6379;
        int database = redis != null ? redis.getDatabase() : 0;
        boolean ssl = redis != null && redis.isSsl();
        String password = redis != null ? redis.getPassword() : null;
        Duration timeout = redis != null ? redis.getConnectTimeout() : Duration.ofSeconds(2);

        RedisURI.Builder builder = RedisURI.Builder.redis(host, port);
        builder.withDatabase(database);
        builder.withTimeout(timeout);
        if (ssl) {
            builder.withSsl(true);
        }
        if (StringUtils.hasText(password)) {
            builder.withPassword(password.toCharArray());
        }
        return builder.build();
    }
}
