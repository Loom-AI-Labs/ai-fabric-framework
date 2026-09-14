package ai.fabric.execution.chain.state;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local repository used by explicit ephemeral chain deployments. */
public final class InMemorySpecialistChainExecutionRepository
    implements SpecialistChainExecutionRepository {

    private final Map<String, SpecialistChainExecutionRecord> records =
        new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex =
        new ConcurrentHashMap<>();

    @Override
    public synchronized SpecialistChainExecutionRecord create(
        SpecialistChainExecutionRecord record
    ) {
        if (records.containsKey(record.executionId())
            || idempotencyIndex.containsKey(
                record.idempotencyFingerprint()
            )) {
            throw new DuplicateChainExecutionException(
                "Duplicate chain execution ID or idempotency fingerprint"
            );
        }
        records.put(record.executionId(), record);
        idempotencyIndex.put(
            record.idempotencyFingerprint(),
            record.executionId()
        );
        return record;
    }

    @Override
    public Optional<SpecialistChainExecutionRecord> findById(
        String executionId
    ) {
        if (executionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(executionId.trim()));
    }

    @Override
    public Optional<SpecialistChainExecutionRecord>
        findByIdempotencyFingerprint(String idempotencyFingerprint) {
        if (idempotencyFingerprint == null) {
            return Optional.empty();
        }
        String executionId = idempotencyIndex.get(
            idempotencyFingerprint.trim()
        );
        return executionId == null
            ? Optional.empty()
            : Optional.ofNullable(records.get(executionId));
    }

    @Override
    public synchronized boolean compareAndSet(
        SpecialistChainExecutionRecord expected,
        SpecialistChainExecutionRecord updated
    ) {
        validateTransition(expected, updated);
        SpecialistChainExecutionRecord current = records.get(
            expected.executionId()
        );
        if (!expected.equals(current)) {
            return false;
        }
        records.put(updated.executionId(), updated);
        return true;
    }

    @Override
    public List<SpecialistChainExecutionRecord> findRecoverable(
        Instant now,
        int limit
    ) {
        return records.values().stream()
            .filter(record -> record.claimable(now, Integer.MAX_VALUE))
            .sorted(Comparator.comparing(
                SpecialistChainExecutionRecord::updatedAt
            ))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public List<SpecialistChainExecutionRecord>
        findTerminalCompletedBefore(Instant cutoff, int limit) {
        return records.values().stream()
            .filter(record -> record.status().terminal())
            .filter(record -> record.completedAt() != null)
            .filter(record -> record.completedAt().isBefore(cutoff))
            .sorted(Comparator.comparing(
                SpecialistChainExecutionRecord::completedAt
            ))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public long countActive() {
        return records.values().stream()
            .filter(record -> !record.status().terminal())
            .count();
    }

    @Override
    public synchronized boolean delete(
        SpecialistChainExecutionRecord expected
    ) {
        SpecialistChainExecutionRecord current = records.get(
            expected.executionId()
        );
        if (!expected.equals(current) || !current.status().terminal()) {
            return false;
        }
        records.remove(expected.executionId());
        idempotencyIndex.remove(
            expected.idempotencyFingerprint(),
            expected.executionId()
        );
        return true;
    }

    private void validateTransition(
        SpecialistChainExecutionRecord expected,
        SpecialistChainExecutionRecord updated
    ) {
        if (!expected.executionId().equals(updated.executionId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Chain transition must preserve ID and increment version"
            );
        }
    }

    private long positive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
