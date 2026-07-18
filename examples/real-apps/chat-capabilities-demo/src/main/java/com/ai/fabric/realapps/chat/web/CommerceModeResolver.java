package com.ai.fabric.realapps.chat.web;

import java.util.Locale;
import java.util.Map;
import lombok.Builder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CommerceModeResolver {

    private static final String DEFAULT_POSITION = "landing";
    private static final String DEFAULT_MODE = "navigator";
    private static final Map<String, String> POSITION_TO_MODE = Map.of(
        "landing", "navigator",
        "catalog", "navigator",
        "search", "navigator_deep",
        "product_detail", "cart_assistant",
        "cart", "cart_assistant",
        "checkout", "executor",
        "orders", "cart_assistant",
        "support", "navigator"
    );

    public ResolvedRouting resolve(String requestedPosition, String requestedMode) {
        String position = normalizePosition(requestedPosition);
        if (StringUtils.hasText(requestedMode)) {
            return ResolvedRouting.builder()
                .position(position)
                .mode(requestedMode.trim())
                .modeSource("explicit")
                .build();
        }
        return ResolvedRouting.builder()
            .position(position)
            .mode(POSITION_TO_MODE.getOrDefault(position, DEFAULT_MODE))
            .modeSource("position_default")
            .build();
    }

    private String normalizePosition(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_POSITION;
        }
        String normalized = raw.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        return POSITION_TO_MODE.containsKey(normalized) ? normalized : DEFAULT_POSITION;
    }

    @Builder
    public record ResolvedRouting(String position, String mode, String modeSource) {
    }
}
