package com.ai.infrastructure.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathPromptTemplateStoreTest {

    @Test
    void shouldLoadMultiStepTemplatesFromClasspath() {
        PromptTemplateStore store = new ClasspathPromptTemplateStore(new DefaultResourceLoader());
        PromptRenderer renderer = new PromptRenderer();

        PromptTemplate classify = store.load(new PromptTemplateKey(
            "intent-extraction/multi-step",
            "v1",
            "classify"
        ));

        assertThat(classify.template()).contains("You are classifying");
        String rendered = renderer.render(classify, Map.of("user_query", "hello"));
        assertThat(rendered).contains("USER REQUEST:");
        assertThat(rendered).contains("hello");
    }
}

