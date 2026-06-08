package com.ai.infrastructure.relationship.config;

import com.ai.infrastructure.relationship.model.QueryMode;
import com.ai.infrastructure.relationship.model.ReturnMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipQueryPropertiesTest {

    @Test
    void shouldBindCustomValues() {
        Map<String, String> properties = Map.of(
            "ai.infrastructure.relationship.enabled", "false",
            "ai.infrastructure.relationship.default-return-mode", "full",
            "ai.infrastructure.relationship.default-query-mode", "enhanced",
            "ai.infrastructure.relationship.llm.temperature", "0.2",
            "ai.infrastructure.relationship.planner.log-plans", "true",
            "ai.infrastructure.relationship.planner.min-confidence-to-execute", "0.65"
        );

        RelationshipQueryProperties bound = new Binder(new MapConfigurationPropertySource(properties))
            .bind("ai.infrastructure.relationship", Bindable.of(RelationshipQueryProperties.class))
            .orElseThrow(() -> new IllegalStateException("Failed to bind relationship properties"));

        assertThat(bound.isEnabled()).isFalse();
        assertThat(bound.getDefaultReturnMode()).isEqualTo(ReturnMode.FULL);
        assertThat(bound.getDefaultQueryMode()).isEqualTo(QueryMode.ENHANCED);
        assertThat(bound.getLlm().getTemperature()).isEqualTo(0.2);
        assertThat(bound.getPlanner().isLogPlans()).isTrue();
        assertThat(bound.getPlanner().getMinConfidenceToExecute()).isEqualTo(0.65);
    }
}
