package ai.fabric.intent.action.confirmation;

import ai.fabric.intent.action.PendingAction;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class ConfirmationInterceptorParamSupport {

    private ConfirmationInterceptorParamSupport() {
    }

    public static String normalizeOnceParam(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isGuardSet(PendingAction pendingAction, String key) {
        if (pendingAction == null
            || pendingAction.confirmationInterceptorGuards() == null
            || pendingAction.confirmationInterceptorGuards().isEmpty()
            || !StringUtils.hasText(key)) {
            return false;
        }
        String normalizedKey = normalizeOnceParam(key);
        if (!StringUtils.hasText(normalizedKey)) {
            return false;
        }
        return pendingAction.confirmationInterceptorGuards().contains(normalizedKey);
    }

    public static PendingAction withGuardSet(PendingAction pendingAction, String key) {
        if (pendingAction == null || !StringUtils.hasText(key)) {
            return pendingAction;
        }
        String normalizedKey = normalizeOnceParam(key);
        if (!StringUtils.hasText(normalizedKey) || isGuardSet(pendingAction, normalizedKey)) {
            return pendingAction;
        }
        Set<String> guards = new LinkedHashSet<>(pendingAction.confirmationInterceptorGuards());
        guards.add(normalizedKey);
        return new PendingAction(
            pendingAction.action(),
            pendingAction.actionParams(),
            pendingAction.description(),
            pendingAction.createdAt(),
            pendingAction.trustedEvidenceValuesByKey(),
            pendingAction.trustedResolvedParameters(),
            Set.copyOf(guards)
        );
    }
}
