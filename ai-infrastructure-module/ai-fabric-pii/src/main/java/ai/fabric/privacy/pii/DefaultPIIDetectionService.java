package ai.fabric.privacy.pii;

import ai.fabric.config.PIIDetectionProperties;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.dto.PIIMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Slf4j
public class DefaultPIIDetectionService implements PIIDetectionService {

    private static final int AES_KEY_LENGTH_BYTES = 32;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final PIIDetectionProperties properties;
    private final List<DetectionPattern> detectionPatterns;
    private final SecureRandom secureRandom;

    public DefaultPIIDetectionService(PIIDetectionProperties properties) {
        this(properties, new SecureRandom());
    }

    DefaultPIIDetectionService(PIIDetectionProperties properties, SecureRandom secureRandom) {
        this.properties = Objects.requireNonNull(properties, "PIIDetectionProperties must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "SecureRandom must not be null");
        this.detectionPatterns = buildPatterns(properties);
        log.info("PII detection initialized: enabled={}, mode={}, patterns={}",
            properties.isEnabled(), properties.getMode(), detectionPatterns.size());
    }

    @Override
    public PIIDetectionResult detectAndProcess(String query) {
        if (!StringUtils.hasText(query)) {
            return emptyResult(query);
        }

        if (!properties.isEnabled()) {
            return buildResult(query, query, Collections.emptyList(), false, PIIMode.PASS_THROUGH, null);
        }

        PIIMode mode = Optional.ofNullable(properties.getMode()).orElse(PIIMode.PASS_THROUGH);
        if (mode == PIIMode.PASS_THROUGH) {
            return buildResult(query, query, Collections.emptyList(), false, mode, null);
        }

        List<DetectionMatch> detections = detect(query);
        boolean hasPii = !detections.isEmpty();
        String processedQuery = query;

        if (hasPii && mode == PIIMode.REDACT) {
            processedQuery = redact(query, detections);
        }

        String originalPayloadRecord = null;
        String encryptionSalt = null;
        if (hasPii && properties.isStoreEncryptedOriginal()) {
            EncryptionPayload payload = securePayload(query);
            originalPayloadRecord = payload.encrypted();
            encryptionSalt = payload.salt();
        }

        PIIDetectionResult result = buildResult(
            query,
            processedQuery,
            detections.stream().map(DetectionMatch::toDetection).collect(Collectors.toList()),
            hasPii,
            mode,
            Map.of(
                "patternsEvaluated", detectionPatterns.size(),
                "auditLoggingEnabled", properties.isAuditLoggingEnabled(),
                "piiSensitiveFieldsConfigured", configuredSensitiveFields()
            )
        );
        result.setEncryptedOriginalQuery(originalPayloadRecord);
        result.setEncryptionSalt(encryptionSalt);

        if (hasPii && properties.isAuditLoggingEnabled()) {
            log.info("PII detected - totalDetections={}, mode={}, sensitiveFields={} ",
                result.getDetections().size(), mode, summarizeFields(result.getDetections()));
        } else if (mode == PIIMode.DETECT_ONLY && !hasPii) {
            log.debug("PII detection completed with no matches in DETECT_ONLY mode.");
        }

        return result;
    }

    @Override
    public PIIDetectionResult analyze(String payload) {
        if (!StringUtils.hasText(payload)) {
            return emptyResult(payload);
        }

        if (!properties.isEnabled()) {
            return buildResult(payload, payload, Collections.emptyList(), false, PIIMode.PASS_THROUGH, Map.of(
                "analysisOnly", true
            ));
        }

        PIIMode mode = Optional.ofNullable(properties.getMode()).orElse(PIIMode.PASS_THROUGH);
        if (mode == PIIMode.PASS_THROUGH) {
            return buildResult(payload, payload, Collections.emptyList(), false, PIIMode.PASS_THROUGH, Map.of(
                "analysisOnly", true
            ));
        }

        List<DetectionMatch> detections = detect(payload);
        boolean hasPii = !detections.isEmpty();

        return buildResult(
            payload,
            payload,
            detections.stream().map(DetectionMatch::toDetection).collect(Collectors.toList()),
            hasPii,
            PIIMode.DETECT_ONLY,
            Map.of(
                "analysisOnly", true,
                "patternsEvaluated", detectionPatterns.size()
            )
        );
    }

    private PIIDetectionResult emptyResult(String query) {
        return PIIDetectionResult.builder()
            .originalQuery(query)
            .processedQuery(query)
            .piiDetected(false)
            .detections(Collections.emptyList())
            .modeApplied(PIIMode.PASS_THROUGH)
            .detectedAt(Instant.now())
            .metadata(Collections.emptyMap())
            .build();
    }

    private PIIDetectionResult buildResult(
        String original,
        String processed,
        List<PIIDetection> detections,
        boolean hasPii,
        PIIMode mode,
        Map<String, Object> additionalMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean exposeOriginalPayload = shouldExposeOriginalPayload(hasPii);
        metadata.put("piiDetected", hasPii);
        metadata.put("modeApplied", mode.name());
        metadata.put("timestamp", Instant.now().toString());
        if (additionalMetadata != null) {
            metadata.putAll(additionalMetadata);
        }
        metadata.put("originalPayloadExposed", exposeOriginalPayload);
        if (hasPii && !exposeOriginalPayload) {
            metadata.put("originalPayloadProtected", true);
        }

        return PIIDetectionResult.builder()
            .originalQuery(exposeOriginalPayload ? original : null)
            .processedQuery(processed)
            .piiDetected(hasPii)
            .modeApplied(mode)
            .detections(detections)
            .detectedAt(Instant.now())
            .metadata(metadata)
            .build();
    }

    private String summarizeFields(List<PIIDetection> detections) {
        return detections.stream()
            .map(PIIDetection::getFieldName)
            .distinct()
            .collect(Collectors.joining(","));
    }

    private List<DetectionMatch> detect(String query) {
        if (detectionPatterns.isEmpty()) {
            return Collections.emptyList();
        }

        List<DetectionMatch> matches = new ArrayList<>();
        for (DetectionPattern pattern : detectionPatterns) {
            Matcher matcher = pattern.pattern().matcher(query);
            while (matcher.find()) {
                DetectionMatch match = pattern.createMatch(matcher);
                if (match.startIndex() >= match.endIndex()) {
                    continue;
                }
                if (!overlapsExistingMatch(match, matches)) {
                    matches.add(match);
                }
            }
        }

        matches.sort(Comparator.comparingInt(DetectionMatch::startIndex));
        return matches;
    }

    private boolean overlapsExistingMatch(DetectionMatch candidate, List<DetectionMatch> existing) {
        return existing.stream().anyMatch(match ->
            rangesOverlap(candidate.startIndex(), candidate.endIndex(), match.startIndex(), match.endIndex()));
    }

    private boolean rangesOverlap(int start1, int end1, int start2, int end2) {
        return start1 < end2 && start2 < end1;
    }

    private String redact(String original, List<DetectionMatch> matches) {
        if (matches.isEmpty()) {
            return original;
        }

        StringBuilder sanitized = new StringBuilder();
        int cursor = 0;

        for (DetectionMatch match : matches) {
            sanitized.append(original, cursor, match.startIndex());
            sanitized.append(maskValue(match.maskedValue()));
            cursor = match.endIndex();
        }
        sanitized.append(original.substring(cursor));

        return sanitized.toString();
    }

    private EncryptionPayload securePayload(String payload) {
        if (!StringUtils.hasText(properties.getEncryptionSecret())) {
            byte[] saltBytes = new byte[16];
            secureRandom.nextBytes(saltBytes);
            String salt = Base64.getEncoder().encodeToString(saltBytes);
            String hash = hashPayload(payload, saltBytes);
            return new EncryptionPayload("HASH:" + hash, salt);
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveAesKey(properties.getEncryptionSecret()),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            );

            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);

            return new EncryptionPayload(Base64.getEncoder().encodeToString(buffer.array()),
                Base64.getEncoder().encodeToString(iv));
        } catch (GeneralSecurityException ex) {
            log.warn("Failed to encrypt original payload securely; falling back to hashing.", ex);
            byte[] saltBytes = new byte[16];
            secureRandom.nextBytes(saltBytes);
            String salt = Base64.getEncoder().encodeToString(saltBytes);
            String hash = hashPayload(payload, saltBytes);
            return new EncryptionPayload("HASH:" + hash, salt);
        }
    }

    private String hashPayload(String payload, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hashed = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to hash payload securely", ex);
        }
    }

    private SecretKeySpec deriveAesKey(String secret) throws GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(secret.getBytes(StandardCharsets.UTF_8));
        if (digest.length < AES_KEY_LENGTH_BYTES) {
            byte[] extended = new byte[AES_KEY_LENGTH_BYTES];
            System.arraycopy(digest, 0, extended, 0, digest.length);
            return new SecretKeySpec(extended, "AES");
        }
        return new SecretKeySpec(digest, 0, AES_KEY_LENGTH_BYTES, "AES");
    }

    private List<DetectionPattern> buildPatterns(PIIDetectionProperties properties) {
        Map<String, PIIDetectionProperties.PatternConfig> configured = new LinkedHashMap<>();
        if (properties.getPatterns() != null) {
            configured.putAll(properties.getPatterns());
        }

        return configured.entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue().isEnabled())
            .map(entry -> toDetectionPattern(entry.getKey(), entry.getValue()))
            .collect(Collectors.toUnmodifiableList());
    }

    private DetectionPattern toDetectionPattern(String name, PIIDetectionProperties.PatternConfig config) {
        String type = StringUtils.hasText(name) ? name.toUpperCase(Locale.ROOT) : "CUSTOM";
        String regex = config.getRegex();
        if (!StringUtils.hasText(regex)) {
            throw new IllegalArgumentException("PII detection pattern '" + type + "' must define a non-blank regex");
        }

        return new DetectionPattern(
            type,
            compilePattern(type, regex),
            config.getFieldName(),
            maskValue(config.getReplacement()),
            normalizeConfidence(config.getConfidence()),
            config.getContextNote()
        );
    }

    private Pattern compilePattern(String type, String regex) {
        try {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (canProduceZeroLengthMatch(pattern)) {
                throw new IllegalArgumentException(
                    "PII detection regex for pattern '" + type + "' must not produce zero-length matches"
                );
            }
            return pattern;
        } catch (PatternSyntaxException ex) {
            throw new IllegalArgumentException("Invalid PII detection regex for pattern '" + type + "'", ex);
        }
    }

    private boolean canProduceZeroLengthMatch(Pattern pattern) {
        for (String sample : List.of("", "A", "1", "sk-token", "user@example.com")) {
            Matcher matcher = pattern.matcher(sample);
            while (matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    return true;
                }
            }
        }
        return false;
    }

    private String maskValue(String replacement) {
        return replacement != null ? replacement : "***";
    }

    private double normalizeConfidence(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 1.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, confidence));
    }

    private List<String> configuredSensitiveFields() {
        return properties.getSensitiveFields() != null ? properties.getSensitiveFields() : List.of();
    }

    private boolean shouldExposeOriginalPayload(boolean hasPii) {
        return !hasPii || properties.isExposeOriginalPayloadInResult();
    }

    private record DetectionPattern(
        String type,
        Pattern pattern,
        String fieldName,
        String replacement,
        double confidence,
        String contextNote
    ) {
        DetectionMatch createMatch(Matcher matcher) {
            int start = matcher.start();
            int end = matcher.end();
            return new DetectionMatch(
                type,
                StringUtils.hasText(fieldName) ? fieldName : type.toLowerCase(Locale.ROOT),
                start,
                end,
                replacement,
                confidence,
                contextNote
            );
        }
    }

    private record DetectionMatch(
        String type,
        String fieldName,
        int startIndex,
        int endIndex,
        String maskedValue,
        double confidence,
        String contextNote
    ) {
        PIIDetection toDetection() {
            return PIIDetection.builder()
                .type(type)
                .fieldName(fieldName)
                .startIndex(startIndex)
                .endIndex(endIndex)
                .maskedValue(maskedValue)
                .confidence(confidence)
                .contextNote(contextNote)
                .build();
        }
    }

    private record EncryptionPayload(String encrypted, String salt) { }
}
