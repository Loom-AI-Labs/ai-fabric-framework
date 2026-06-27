package ai.fabric.llm.structured.springai;

import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.llm.structured.DefaultStructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonCallSpec;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.llm.structured.StructuredJsonFailureType;
import ai.fabric.llm.structured.StructuredJsonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiStructuredOutputSupportTest {

    private final DefaultStructuredJsonCallExecutor executor = new DefaultStructuredJsonCallExecutor(
        new StructuredJsonExtractor(),
        new ObjectMapper()
    );

    @Test
    void beanConverterParsesObjectThroughAiFabricRetryExecutor() {
        var output = SpringAiStructuredOutputSupport.bean(Decision.class);

        StructuredJsonResult<Decision> result = executor.execute(
            StructuredJsonCallSpec.<Decision>builder()
                .callName("decision")
                .responseConverter(output.converter())
                .caller(ctx -> AIGenerationResponse.builder()
                    .content("""
                        ```json
                        {"action":"approve","approved":true}
                        ```
                        """)
                    .build())
                .build()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValue()).isEqualTo(new Decision("approve", true));
        assertThat(output.format()).isNotBlank();
        assertThat(output.jsonSchema()).contains("approved");
    }

    @Test
    void mapConverterParsesJsonObject() {
        var output = SpringAiStructuredOutputSupport.map();

        Map<String, Object> value = output.converter().apply("{\"score\":2,\"label\":\"ready\"}");

        assertThat(value)
            .containsEntry("score", 2)
            .containsEntry("label", "ready");
        assertThat(output.format()).isNotBlank();
    }

    @Test
    void stringListConverterParsesCommaSeparatedText() {
        var output = SpringAiStructuredOutputSupport.stringList();

        List<String> value = output.converter().apply("red, blue, green");

        assertThat(value).containsExactly("red", "blue", "green");
        assertThat(output.format()).isNotBlank();
    }

    @Test
    void converterBackedValidationFailureReturnsAiFabricDiagnostics() {
        var output = SpringAiStructuredOutputSupport.bean(Decision.class);

        StructuredJsonResult<Decision> result = executor.execute(
            StructuredJsonCallSpec.<Decision>builder()
                .callName("decision")
                .responseConverter(output.converter())
                .validator(decision -> {
                    if (!decision.approved()) {
                        throw new IllegalArgumentException("decision must be approved");
                    }
                })
                .caller(ctx -> AIGenerationResponse.builder()
                    .content("{\"action\":\"delete\",\"approved\":false}")
                    .build())
                .build()
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getLastFailure().type()).isEqualTo(StructuredJsonFailureType.VALIDATION_ERROR);
        assertThat(result.getLastFailure().message()).contains("decision must be approved");
    }

    private record Decision(String action, boolean approved) {
    }
}
