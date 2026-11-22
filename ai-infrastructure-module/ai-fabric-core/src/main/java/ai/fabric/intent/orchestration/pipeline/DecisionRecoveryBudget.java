package ai.fabric.intent.orchestration.pipeline;

import ai.fabric.config.DecisionRecoveryProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Server-only request state shared by extraction, routing and compound children. */
public final class DecisionRecoveryBudget {
    private long startedNanos;
    private int rounds;
    private boolean finalAnswerAttempted;
    private boolean providerFailed;
    private final List<Map<String, Object>> attempts = new ArrayList<>();

    public synchronized boolean acquire(DecisionRecoveryProperties settings, String stage) {
        if (settings == null || !settings.isEnabled() || providerFailed || finalAnswerAttempted
            || Thread.currentThread().isInterrupted()) return false;
        if (startedNanos == 0) startedNanos = System.nanoTime();
        if (rounds >= settings.getMaxRepairRounds()
            || elapsedMs() >= settings.getRepairAdmissionTimeoutMs()) return false;
        rounds++;
        attempts.add(Map.of("round", rounds, "stage", stage));
        return true;
    }

    public synchronized void providerFailed() { providerFailed = true; }
    public synchronized boolean isProviderFailed() { return providerFailed; }
    public synchronized boolean hasActivity() { return rounds > 0 || finalAnswerAttempted; }

    public synchronized void recordOutcome(Map<String, Object> outcome) {
        if (attempts.isEmpty()) return;
        Map<String, Object> event = new LinkedHashMap<>(attempts.getLast());
        event.putAll(outcome);
        attempts.set(attempts.size() - 1, Map.copyOf(event));
    }

    public synchronized boolean acquireFinalAnswer(DecisionRecoveryProperties settings) {
        if (settings == null || !settings.isEnabled() || !settings.isFinalContextAnswerEnabled()
            || finalAnswerAttempted || providerFailed || Thread.currentThread().isInterrupted()) return false;
        finalAnswerAttempted = true;
        return true;
    }

    public synchronized int remaining(DecisionRecoveryProperties settings) {
        return Math.max(0, settings.getMaxRepairRounds() - rounds);
    }

    public synchronized Map<String, Object> diagnostics(DecisionRecoveryProperties settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", "REPAIR_THEN_CONTEXT_ANSWER");
        result.put("maxRepairRounds", settings.getMaxRepairRounds());
        result.put("repairRounds", rounds);
        result.put("repairAdmissionTimeoutMs", settings.getRepairAdmissionTimeoutMs());
        result.put("elapsedRecoveryWindowMs", elapsedMs());
        result.put("finalContextAnswerAttempted", finalAnswerAttempted);
        result.put("providerFailed", providerFailed);
        result.put("attempts", List.copyOf(attempts));
        return Map.copyOf(result);
    }

    private long elapsedMs() {
        return startedNanos == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
