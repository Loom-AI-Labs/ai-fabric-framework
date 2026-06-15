package ai.fabric.relay.service;

import ai.fabric.relay.api.ActionExecuteRequestDto;
import ai.fabric.relay.api.ActionResultDto;
import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import ai.fabric.relay.store.RelayKeyValueStore;
import ai.fabric.relay.util.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class IdempotencyStore {

    private static final String ERROR_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    private static final String ERROR_IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayKeyValueStore store;

    public IdempotencyStore(RelayProperties properties, ObjectMapper objectMapper, RelayKeyValueStore store) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.store = store;
    }

    public ActionResultDto executeIdempotent(ActionExecuteRequestDto request, Supplier<ActionResultDto> executor) {
        RelayProperties.Idempotency cfg = properties != null ? properties.getIdempotency() : null;
        if (cfg == null || !cfg.isEnabled()) {
            return executor.get();
        }

        String key = request != null ? request.idempotencyKey() : null;
        if (!StringUtils.hasText(key)) {
            return executor.get();
        }

        String fingerprint = fingerprint(request);
        Duration ttl = Duration.ofSeconds(Math.max(60, cfg.getTtlSeconds()));

        String storageKey = "idempotency:" + Hashing.sha256Hex(key.trim());
        String inProgressJson = toJson(new StoredEntry(Status.IN_PROGRESS, fingerprint, null));

        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (store.putIfAbsent(storageKey, inProgressJson, ttl)) {
                    try {
                        ActionResultDto result = executor.get();
                        if (result != null) {
                            store.put(storageKey, toJson(new StoredEntry(Status.DONE, fingerprint, result)), ttl);
                        } else {
                            store.delete(storageKey);
                        }
                        return result;
                    } catch (Exception ex) {
                        store.delete(storageKey);
                        throw ex;
                    }
                }

                StoredEntry existing = readStored(storageKey);
                if (existing == null) {
                    continue;
                }

                if (!fingerprint.equals(existing.fingerprint)) {
                    return ActionResultDto.failure(ERROR_IDEMPOTENCY_CONFLICT, "Idempotency conflict.");
                }

                if (existing.status == Status.DONE && existing.result != null) {
                    return existing.result;
                }

                if (existing.status == Status.IN_PROGRESS) {
                    return waitForInProgress(cfg, storageKey, fingerprint);
                }

                throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVICE_UNAVAILABLE, "Idempotency store returned an invalid entry.");
            }

            throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVICE_UNAVAILABLE, "Idempotency store unavailable.");
        } catch (RelayRequestRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVICE_UNAVAILABLE, "Idempotency store unavailable.");
        }
    }

    private ActionResultDto waitForInProgress(RelayProperties.Idempotency cfg, String storageKey, String fingerprint) {
        int maxWaitMs = cfg != null ? cfg.getInProgressMaxWaitMs() : 0;
        if (maxWaitMs <= 0) {
            return ActionResultDto.failure(ERROR_IDEMPOTENCY_IN_PROGRESS, "Idempotency key is in progress.");
        }

        long deadlineNanos = System.nanoTime() + (maxWaitMs * 1_000_000L);
        long sleepMs = 25;

        while (System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }

            StoredEntry entry = readStored(storageKey);
            if (entry == null) {
                continue;
            }

            if (!fingerprint.equals(entry.fingerprint)) {
                return ActionResultDto.failure(ERROR_IDEMPOTENCY_CONFLICT, "Idempotency conflict.");
            }

            if (entry.status == Status.DONE && entry.result != null) {
                return entry.result;
            }

            sleepMs = Math.min(200, sleepMs * 2);
        }

        return ActionResultDto.failure(ERROR_IDEMPOTENCY_IN_PROGRESS, "Idempotency key is in progress.");
    }

    private StoredEntry readStored(String storageKey) {
        String json = store.get(storageKey);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredEntry.class);
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVICE_UNAVAILABLE, "Idempotency store returned invalid data.");
        }
    }

    private String toJson(StoredEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVICE_UNAVAILABLE, "Idempotency store unavailable.");
        }
    }

    private String fingerprint(ActionExecuteRequestDto request) {
        String actionId = request != null && StringUtils.hasText(request.actionId()) ? request.actionId().trim() : "";
        Map<String, Object> params = request != null && request.params() != null ? request.params() : Map.of();
        try {
            String canonicalParams = objectMapper.writeValueAsString(params);
            return Hashing.sha256Hex(actionId + "\n" + canonicalParams);
        } catch (Exception ex) {
            return Hashing.sha256Hex(actionId + "\n" + params.toString());
        }
    }

    private enum Status {
        IN_PROGRESS,
        DONE
    }

    private record StoredEntry(Status status, String fingerprint, ActionResultDto result) {
    }
}
