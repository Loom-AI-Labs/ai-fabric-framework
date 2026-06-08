package com.ai.infrastructure.llm.structured;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredJsonExtractorTest {

    private final StructuredJsonExtractor extractor = new StructuredJsonExtractor();

    @Test
    void shouldExtractJsonObjectFromCodeFence() {
        String response = """
            Sure, here you go:
            ```json
            {"a": 1, "b": "x"}
            ```
            """;

        StructuredJsonExtraction extraction = extractor.extractFirstJson(response);

        assertThat(extraction.jsonFound()).isTrue();
        assertThat(extraction.truncationSuspected()).isFalse();
        assertThat(extraction.payload()).isEqualTo("{\"a\": 1, \"b\": \"x\"}");
    }

    @Test
    void shouldExtractJsonArrayPayload() {
        String response = "Result: [1, 2, 3]";

        StructuredJsonExtraction extraction = extractor.extractFirstJson(response);

        assertThat(extraction.jsonFound()).isTrue();
        assertThat(extraction.payload()).isEqualTo("[1, 2, 3]");
    }

    @Test
    void shouldNotBreakOnBracesInsideStrings() {
        String response = """
            NOTE:
            {"text": "value with } brace", "items": [1, 2]}
            trailing text
            """;

        StructuredJsonExtraction extraction = extractor.extractFirstJson(response);

        assertThat(extraction.jsonFound()).isTrue();
        assertThat(extraction.truncationSuspected()).isFalse();
        assertThat(extraction.payload()).contains("\"value with } brace\"");
        assertThat(extraction.payload()).endsWith("}");
    }

    @Test
    void shouldMarkTruncationWhenJsonEnvelopeIsUnbalanced() {
        String response = "{\"a\": 1, \"b\": {\"c\": 2}";

        StructuredJsonExtraction extraction = extractor.extractFirstJson(response);

        assertThat(extraction.jsonFound()).isTrue();
        assertThat(extraction.truncationSuspected()).isTrue();
        assertThat(extraction.payload()).startsWith("{\"a\": 1");
    }
}

