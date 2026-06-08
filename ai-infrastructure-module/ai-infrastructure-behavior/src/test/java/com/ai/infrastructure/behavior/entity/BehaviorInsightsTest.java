package com.ai.infrastructure.behavior.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorInsightsTest {

    @Test
    void prePersistSetsTimestamps() {
        BehaviorInsights insight = BehaviorInsights.builder()
            .segment("seg")
            .patterns(List.of("a"))
            .recommendations(List.of("r"))
            .insights(Map.of("k", "v"))
            .build();

        insight.onCreate();

        assertThat(insight.getCreatedAt()).isNotNull();
        assertThat(insight.getUpdatedAt()).isNotNull();
    }

    @Test
    void preUpdateRefreshesUpdatedAt() throws InterruptedException {
        BehaviorInsights insight = BehaviorInsights.builder()
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        LocalDateTime before = insight.getUpdatedAt();
        Thread.sleep(5);
        insight.onUpdate();

        assertThat(insight.getUpdatedAt()).isAfter(before);
    }

    @Test
    void searchableContentFormatsFields() {
        BehaviorInsights insight = BehaviorInsights.builder()
            .segment("Power")
            .patterns(List.of("login", "buy"))
            .recommendations(List.of("upsell"))
            .confidence(0.42)
            .build();

        String content = insight.getSearchableContent();

        assertThat(content).contains("Power").contains("login, buy").contains("upsell").contains("0.42");
    }
}
