package ai.fabric.intent.action.confirmation;

import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

public final class ConfirmationInterceptorParamSupport {

    private ConfirmationInterceptorParamSupport() {
    }

    public static String normalizeOnceParam(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isBooleanFlagSet(Map<String, Object> params, String key) {
        if (params == null || params.isEmpty() || !StringUtils.hasText(key)) {
            return false;
        }
        String normalizedKey = normalizeOnceParam(key);
        if (!StringUtils.hasText(normalizedKey)) {
            return false;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            if (normalizeOnceParam(entry.getKey()).equals(normalizedKey)
                && Boolean.TRUE.equals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }
}
