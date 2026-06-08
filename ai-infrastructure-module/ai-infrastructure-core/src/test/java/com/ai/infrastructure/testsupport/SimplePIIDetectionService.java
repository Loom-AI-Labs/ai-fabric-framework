package com.ai.infrastructure.testsupport;

import com.ai.infrastructure.dto.PIIDetection;
import com.ai.infrastructure.dto.PIIDetectionResult;
import com.ai.infrastructure.dto.PIIMode;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal test-only {@link PIIDetectionService} implementation for core unit tests.
 */
public class SimplePIIDetectionService implements PIIDetectionService {

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CREDIT_CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,16}(?!\\d)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("(?:(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.-]?\\d{3}[\\s.-]?\\d{4})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Override
    public PIIDetectionResult detectAndProcess(String query) {
        PIIDetectionResult analysis = analyze(query);
        if (analysis == null || !analysis.isPiiDetected() || !StringUtils.hasText(query)) {
            return analysis;
        }

        String redacted = redact(query, analysis.getDetections());
        analysis.setProcessedQuery(redacted);
        analysis.setModeApplied(PIIMode.REDACT);
        return analysis;
    }

    @Override
    public PIIDetectionResult analyze(String payload) {
        if (!StringUtils.hasText(payload)) {
            return PIIDetectionResult.builder()
                .originalQuery(payload)
                .processedQuery(payload)
                .piiDetected(false)
                .detections(Collections.emptyList())
                .modeApplied(PIIMode.PASS_THROUGH)
                .detectedAt(Instant.now())
                .metadata(Collections.emptyMap())
                .build();
        }

        List<PIIDetection> detections = new ArrayList<>();
        detectInto(detections, payload, EMAIL, "EMAIL", "***@***.***", "email");
        detectInto(detections, payload, CREDIT_CARD, "CREDIT_CARD", "****-****-****-****", "credit_card");
        detectInto(detections, payload, PHONE, "PHONE", "***-***-****", "phone_number");

        boolean piiDetected = !detections.isEmpty();
        detections.sort(Comparator.comparingInt(PIIDetection::getStartIndex));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisOnly", true);

        return PIIDetectionResult.builder()
            .originalQuery(payload)
            .processedQuery(payload)
            .piiDetected(piiDetected)
            .detections(Collections.unmodifiableList(detections))
            .modeApplied(PIIMode.DETECT_ONLY)
            .detectedAt(Instant.now())
            .metadata(Collections.unmodifiableMap(metadata))
            .build();
    }

    private void detectInto(List<PIIDetection> detections,
                            String payload,
                            Pattern pattern,
                            String type,
                            String maskedValue,
                            String fieldName) {
        Matcher matcher = pattern.matcher(payload);
        while (matcher.find()) {
            detections.add(PIIDetection.builder()
                .type(type)
                .fieldName(fieldName)
                .startIndex(matcher.start())
                .endIndex(matcher.end())
                .maskedValue(maskedValue)
                .build());
        }
    }

    private String redact(String original, List<PIIDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            return original;
        }

        StringBuilder builder = new StringBuilder(original);
        detections.stream()
            .filter(d -> StringUtils.hasText(d.getMaskedValue()))
            .sorted((a, b) -> Integer.compare(b.getStartIndex(), a.getStartIndex()))
            .forEach(detection -> {
                int start = Math.max(0, Math.min(detection.getStartIndex(), builder.length()));
                int end = Math.max(start, Math.min(detection.getEndIndex(), builder.length()));
                builder.replace(start, end, detection.getMaskedValue());
            });
        return builder.toString();
    }
}

