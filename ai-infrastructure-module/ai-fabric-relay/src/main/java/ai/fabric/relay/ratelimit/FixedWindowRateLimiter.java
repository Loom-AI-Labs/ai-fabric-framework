package ai.fabric.relay.ratelimit;

import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import ai.fabric.relay.store.RelayKeyValueStore;
import ai.fabric.relay.util.Hashing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

@Service
public class FixedWindowRateLimiter {

    private final RelayProperties properties;
    private final RelayKeyValueStore store;
    private final Clock clock;

    @Autowired
    public FixedWindowRateLimiter(RelayProperties properties, RelayKeyValueStore store) {
        this(properties, store, Clock.systemUTC());
    }

    FixedWindowRateLimiter(RelayProperties properties, RelayKeyValueStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void check(String userKey, String actionId) {
        String user = StringUtils.hasText(userKey) ? userKey.trim() : "unknown";
        String action = StringUtils.hasText(actionId) ? actionId.trim() : "unknown";
        String userHash = Hashing.sha256Hex(user);
        checkWindow("rl:user:" + userHash, perUserConfig());

        String actionHash = Hashing.sha256Hex(action);
        checkWindow("rl:action:" + userHash + ":" + actionHash, perActionConfig(action));
    }

    private void checkWindow(String key, RelayProperties.Window window) {
        if (window == null) {
            return;
        }
        int windowSeconds = Math.max(1, window.getWindowSeconds());
        int maxRequests = Math.max(1, window.getMaxRequests());

        long now = Instant.now(clock).getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        long windowEnd = windowStart + windowSeconds;
        long retryAfter = windowEnd - now;

        String counterKey = key + ":" + windowStart;
        long count;
        try {
            count = store.increment(counterKey, Duration.ofSeconds(windowSeconds + 5L));
        } catch (RateLimitedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVICE_UNAVAILABLE", "Rate limit store unavailable.");
        }

        if (count > maxRequests) {
            throw new RateLimitedException((int) Math.max(1, retryAfter));
        }
    }

    private RelayProperties.Window perUserConfig() {
        RelayProperties.RateLimits rateLimits = properties != null ? properties.getRateLimits() : null;
        return rateLimits != null ? rateLimits.getPerUser() : null;
    }

    private RelayProperties.Window perActionConfig(String actionId) {
        RelayProperties.RateLimits rateLimits = properties != null ? properties.getRateLimits() : null;
        Map<String, RelayProperties.Window> perAction = rateLimits != null ? rateLimits.getPerAction() : null;
        if (perAction == null || perAction.isEmpty() || !StringUtils.hasText(actionId)) {
            return null;
        }
        return perAction.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().equals(actionId))
            .map(Map.Entry::getValue)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}
