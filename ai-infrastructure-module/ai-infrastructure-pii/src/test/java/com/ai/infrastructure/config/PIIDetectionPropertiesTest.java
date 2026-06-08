package com.ai.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PIIDetectionPropertiesTest {

    @Test
    void defaultDetectionDirectionIsInputOutput() {
        PIIDetectionProperties properties = new PIIDetectionProperties();

        assertThat(properties.getDetectionDirection())
            .isEqualTo(PIIDetectionProperties.PIIDetectionDirection.INPUT_OUTPUT);
    }

    @Test
    void canSwitchDetectionDirectionToInputOnly() {
        PIIDetectionProperties properties = new PIIDetectionProperties();

        properties.setDetectionDirection(PIIDetectionProperties.PIIDetectionDirection.INPUT);

        assertThat(properties.getDetectionDirection())
            .isEqualTo(PIIDetectionProperties.PIIDetectionDirection.INPUT);
    }

    @Test
    void enumOnlyExposesSupportedValues() {
        PIIDetectionProperties.PIIDetectionDirection[] values =
            PIIDetectionProperties.PIIDetectionDirection.values();

        assertThat(values)
            .containsExactlyInAnyOrder(
                PIIDetectionProperties.PIIDetectionDirection.INPUT,
                PIIDetectionProperties.PIIDetectionDirection.INPUT_OUTPUT
            );
    }
}

