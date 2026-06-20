package ai.fabric.llm.structured.springai;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class SpringAiStructuredOutputSupport {

    private SpringAiStructuredOutputSupport() {
    }

    public static <T> SpringAiStructuredOutput<T> bean(Class<T> targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("Target type cannot be null");
        }
        return from(new BeanOutputConverter<>(targetType));
    }

    public static SpringAiStructuredOutput<Map<String, Object>> map() {
        return from(new MapOutputConverter());
    }

    public static SpringAiStructuredOutput<List<String>> stringList() {
        return from(new ListOutputConverter());
    }

    private static <T> SpringAiStructuredOutput<T> from(StructuredOutputConverter<T> converter) {
        return new SpringAiStructuredOutput<>(
            converter.getFormat(),
            normalizeJsonSchema(converter.getJsonSchema()),
            converter::convert
        );
    }

    private static String normalizeJsonSchema(String jsonSchema) {
        return StringUtils.hasText(jsonSchema)
            && !StructuredOutputConverter.NO_JSON_SCHEMA.equals(jsonSchema)
            ? jsonSchema
            : null;
    }

    public record SpringAiStructuredOutput<T>(
        String format,
        String jsonSchema,
        Function<String, T> converter
    ) {
    }
}
