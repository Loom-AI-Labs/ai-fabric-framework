package ai.fabric.llm.structured;

import ai.fabric.dto.AIGenerationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultStructuredJsonCallExecutor implements StructuredJsonCallExecutor {

    private final StructuredJsonExtractor extractor;
    private final ObjectMapper objectMapper;

    @Override
    public <T> StructuredJsonResult<T> execute(StructuredJsonCallSpec<T> spec) {
        if (spec == null) {
            throw new IllegalArgumentException("StructuredJsonCallSpec cannot be null");
        }
        if (spec.getCaller() == null) {
            throw new IllegalArgumentException("StructuredJsonCallSpec.caller cannot be null");
        }
        if (spec.getTargetType() == null && spec.getResponseConverter() == null) {
            throw new IllegalArgumentException("StructuredJsonCallSpec.targetType or responseConverter must be supplied");
        }

        String callName = StringUtils.hasText(spec.getCallName()) ? spec.getCallName() : "structured_json";
        int maxAttempts = Math.max(1, spec.getMaxAttempts());
        ObjectMapper mapper = spec.getObjectMapper() != null ? spec.getObjectMapper() : objectMapper;

        List<StructuredJsonFailure> failures = new ArrayList<>();
        String previousRaw = null;
        String previousJson = null;

        for (int attemptIndex = 0; attemptIndex < maxAttempts; attemptIndex++) {
            StructuredJsonAttemptContext context = new StructuredJsonAttemptContext(
                attemptIndex,
                Collections.unmodifiableList(failures),
                previousRaw,
                previousJson
            );

            AIGenerationResponse response;
            try {
                response = spec.getCaller().apply(context);
            } catch (Exception ex) {
                StructuredJsonFailure failure = new StructuredJsonFailure(
                    StructuredJsonFailureType.CALL_ERROR,
                    safeMessage(ex),
                    false
                );
                failures.add(failure);
                boolean shouldRetry = spec.isRetryOnCallError() && attemptIndex < maxAttempts - 1;
                if (shouldRetry) {
                    log.debug("Structured JSON call '{}' attempt {} failed to call provider (will retry): {}", callName, attemptIndex + 1, failure.message());
                } else {
                    log.warn("Structured JSON call '{}' attempt {} failed to call provider: {}", callName, attemptIndex + 1, failure.message());
                }
                if (!shouldRetry) {
                    return StructuredJsonResult.<T>builder()
                        .success(false)
                        .attempts(attemptIndex + 1)
                        .failures(Collections.unmodifiableList(failures))
                        .lastFailure(failure)
                        .build();
                }
                continue;
            }

            String rawContent = response != null ? response.getContent() : null;
            previousRaw = rawContent;
            if (!StringUtils.hasText(rawContent)) {
                StructuredJsonFailure failure = new StructuredJsonFailure(
                    StructuredJsonFailureType.EMPTY_RESPONSE,
                    "Empty response from provider",
                    false
                );
                failures.add(failure);
                boolean lastAttempt = attemptIndex == maxAttempts - 1;
                if (lastAttempt) {
                    log.warn("Structured JSON call '{}' attempt {} returned empty content", callName, attemptIndex + 1);
                    return StructuredJsonResult.<T>builder()
                        .success(false)
                        .attempts(attemptIndex + 1)
                        .failures(Collections.unmodifiableList(failures))
                        .lastFailure(failure)
                        .build();
                }
                log.debug("Structured JSON call '{}' attempt {} returned empty content (will retry)", callName, attemptIndex + 1);
                continue;
            }

            StructuredJsonExtraction extraction = extractor.extractFirstJson(rawContent);
            previousJson = extraction.payload();
            if (!extraction.jsonFound() || !StringUtils.hasText(extraction.payload())) {
                StructuredJsonFailure failure = new StructuredJsonFailure(
                    StructuredJsonFailureType.NO_JSON_FOUND,
                    "No JSON payload found in provider response",
                    false
                );
                failures.add(failure);
                boolean lastAttempt = attemptIndex == maxAttempts - 1;
                if (lastAttempt) {
                    log.warn("Structured JSON call '{}' attempt {} did not contain JSON", callName, attemptIndex + 1);
                    return StructuredJsonResult.<T>builder()
                        .success(false)
                        .attempts(attemptIndex + 1)
                        .failures(Collections.unmodifiableList(failures))
                        .lastFailure(failure)
                        .build();
                }
                log.debug("Structured JSON call '{}' attempt {} did not contain JSON (will retry)", callName, attemptIndex + 1);
                continue;
            }

            T parsed;
            try {
                parsed = spec.getResponseConverter() != null
                    ? spec.getResponseConverter().apply(extraction.payload())
                    : mapper.readValue(extraction.payload(), spec.getTargetType());
            } catch (Exception ex) {
                StructuredJsonFailure failure = new StructuredJsonFailure(
                    StructuredJsonFailureType.PARSE_ERROR,
                    "Unable to parse structured JSON: " + safeMessage(ex),
                    extraction.truncationSuspected()
                );
                failures.add(failure);
                boolean lastAttempt = attemptIndex == maxAttempts - 1;
                if (lastAttempt) {
                    log.warn("Structured JSON call '{}' attempt {} failed to parse: {}", callName, attemptIndex + 1, failure.message());
                    return StructuredJsonResult.<T>builder()
                        .success(false)
                        .attempts(attemptIndex + 1)
                        .failures(Collections.unmodifiableList(failures))
                        .lastFailure(failure)
                        .build();
                }
                log.debug("Structured JSON call '{}' attempt {} failed to parse (will retry): {}", callName, attemptIndex + 1, failure.message());
                continue;
            }

            if (spec.getValidator() != null) {
                try {
                    spec.getValidator().accept(parsed);
                } catch (Exception ex) {
                    StructuredJsonFailure failure = new StructuredJsonFailure(
                        StructuredJsonFailureType.VALIDATION_ERROR,
                        safeMessage(ex),
                        false
                    );
                    failures.add(failure);
                    boolean lastAttempt = attemptIndex == maxAttempts - 1;
                    if (lastAttempt) {
                        log.warn("Structured JSON call '{}' attempt {} failed validation: {}", callName, attemptIndex + 1, failure.message());
                        return StructuredJsonResult.<T>builder()
                            .success(false)
                            .attempts(attemptIndex + 1)
                            .failures(Collections.unmodifiableList(failures))
                            .lastFailure(failure)
                            .build();
                    }
                    log.debug("Structured JSON call '{}' attempt {} failed validation (will retry): {}", callName, attemptIndex + 1, failure.message());
                    continue;
                }
            }

            return StructuredJsonResult.<T>builder()
                .success(true)
                .value(parsed)
                .attempts(attemptIndex + 1)
                .failures(Collections.unmodifiableList(failures))
                .lastFailure(failures.isEmpty() ? null : failures.get(failures.size() - 1))
                .build();
        }

        StructuredJsonFailure last = failures.isEmpty()
            ? new StructuredJsonFailure(StructuredJsonFailureType.CALL_ERROR, "Unknown structured JSON failure", false)
            : failures.get(failures.size() - 1);

        return StructuredJsonResult.<T>builder()
            .success(false)
            .attempts(maxAttempts)
            .failures(Collections.unmodifiableList(failures))
            .lastFailure(last)
            .build();
    }

    private String safeMessage(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }
}
