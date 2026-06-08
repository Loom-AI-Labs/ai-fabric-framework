package com.ai.infrastructure.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    @Test
    void shouldRenderAndReplacePlaceholders() {
        PromptTemplate template = new PromptTemplate(
            new PromptTemplateKey("unit", "v1", "hello"),
            "Hello {{name}}!"
        );

        String rendered = renderer.render(template, Map.of("name", "world"));
        assertThat(rendered).isEqualTo("Hello world!");
    }

    @Test
    void shouldFailClosedOnMissingPlaceholder() {
        PromptTemplate template = new PromptTemplate(
            new PromptTemplateKey("unit", "v1", "hello"),
            "Hello {{name}}!"
        );

        assertThatThrownBy(() -> renderer.render(template, Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing prompt placeholders");
    }
}

