package ai.fabric.execution.action;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts executable payloads and fingerprints trusted identity without storing raw IDs.
 */
public final class ActionProposalSecurity {

    private static final String PAYLOAD_VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final ObjectMapper canonicalMapper;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;
    private final SecureRandom secureRandom;

    public ActionProposalSecurity(
        ObjectMapper objectMapper,
        String encryptionSecret,
        String fingerprintSecret
    ) {
        this(
            objectMapper,
            encryptionSecret,
            fingerprintSecret,
            new SecureRandom()
        );
    }

    ActionProposalSecurity(
        ObjectMapper objectMapper,
        String encryptionSecret,
        String fingerprintSecret,
        SecureRandom secureRandom
    ) {
        this.canonicalMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        ).copy();
        this.canonicalMapper.configure(
            SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
            true
        );
        this.canonicalMapper.configure(
            MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,
            true
        );
        byte[] encryptionSecretBytes = secret(
            encryptionSecret,
            "encryptionSecret"
        );
        byte[] fingerprintSecretBytes = secret(
            fingerprintSecret,
            "fingerprintSecret"
        );
        if (MessageDigest.isEqual(
            encryptionSecretBytes,
            fingerprintSecretBytes
        )) {
            throw new IllegalArgumentException(
                "encryptionSecret and fingerprintSecret must be different"
            );
        }
        this.encryptionKey = new SecretKeySpec(
            sha256(encryptionSecretBytes),
            "AES"
        );
        this.fingerprintKey = new SecretKeySpec(
            sha256(fingerprintSecretBytes),
            "HmacSHA256"
        );
        this.secureRandom = Objects.requireNonNull(
            secureRandom,
            "secureRandom is required"
        );
    }

    public String protect(Map<String, Object> payload, String binding) {
        Objects.requireNonNull(payload, "payload is required");
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                encryptionKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            cipher.updateAAD(requireBinding(binding));
            byte[] encrypted = cipher.doFinal(canonicalBytes(payload));
            ByteBuffer envelope = ByteBuffer.allocate(iv.length + encrypted.length);
            envelope.put(iv);
            envelope.put(encrypted);
            return PAYLOAD_VERSION
                + "."
                + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(envelope.array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                "Could not protect action proposal payload",
                ex
            );
        }
    }

    public Map<String, Object> unprotect(String protectedPayload, String binding) {
        String[] parts = Objects.requireNonNull(
            protectedPayload,
            "protectedPayload is required"
        ).split("\\.", 2);
        if (parts.length != 2 || !PAYLOAD_VERSION.equals(parts[0])) {
            throw new IllegalArgumentException(
                "Unsupported protected payload version"
            );
        }
        byte[] envelope;
        try {
            envelope = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Protected payload is invalid",
                ex
            );
        }
        if (envelope.length <= IV_BYTES) {
            throw new IllegalArgumentException("Protected payload is invalid");
        }
        byte[] iv = java.util.Arrays.copyOfRange(envelope, 0, IV_BYTES);
        byte[] encrypted = java.util.Arrays.copyOfRange(
            envelope,
            IV_BYTES,
            envelope.length
        );
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            cipher.updateAAD(requireBinding(binding));
            byte[] clear = cipher.doFinal(encrypted);
            return canonicalMapper.readValue(
                clear,
                new TypeReference<Map<String, Object>>() {}
            );
        } catch (GeneralSecurityException | java.io.IOException ex) {
            throw new IllegalStateException(
                "Protected action proposal payload could not be verified",
                ex
            );
        }
    }

    public String principalFingerprint(TrustedExecutionContext context) {
        Objects.requireNonNull(context, "trusted context is required");
        return fingerprint(
            "principal",
            context.initiator().principalType().name()
                + ":"
                + context.initiator().principalId()
        );
    }

    public String subjectFingerprint(TrustedExecutionContext context) {
        Objects.requireNonNull(context, "trusted context is required");
        if (context.subject() == null) {
            throw new IllegalArgumentException(
                "Specialist write proposals require a trusted subject"
            );
        }
        return fingerprint(
            "subject",
            context.subject().subjectType() + ":" + context.subject().subjectId()
        );
    }

    public String tenantFingerprint(TrustedExecutionContext context) {
        return fingerprint("tenant", optional(context.tenantId()));
    }

    public String deploymentFingerprint(TrustedExecutionContext context) {
        return fingerprint("deployment", optional(context.deploymentId()));
    }

    public String idempotencyFingerprint(
        TrustedExecutionContext context,
        SpecialistId specialistId,
        String idempotencyKey
    ) {
        Objects.requireNonNull(context, "trusted context is required");
        Objects.requireNonNull(specialistId, "specialistId is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return fingerprint(
            "idempotency",
            principalFingerprint(context)
                + ":"
                + subjectFingerprint(context)
                + ":"
                + tenantFingerprint(context)
                + ":"
                + deploymentFingerprint(context)
                + ":"
                + specialistId
                + ":"
                + idempotencyKey.trim()
        );
    }

    public String canonicalHash(Object value) {
        return hex(sha256(canonicalBytes(value)));
    }

    public List<String> evidenceHashes(List<AIEvidenceReference> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
            .filter(Objects::nonNull)
            .map(this::evidenceHash)
            .sorted()
            .toList();
    }

    public boolean sameFingerprint(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String fingerprint(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                "Could not fingerprint trusted execution context",
                ex
            );
        }
    }

    private String evidenceHash(AIEvidenceReference reference) {
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("evidenceId", reference.evidenceId());
        canonical.put("contentHash", canonicalHash(reference.content()));
        if (reference.vectorSpace() != null) {
            canonical.put("vectorSpace", reference.vectorSpace());
        }
        if (reference.source() != null) {
            canonical.put("source", reference.source());
        }
        if (reference.sourceUrl() != null) {
            canonical.put("sourceUrl", reference.sourceUrl());
        }
        canonical.put("safeMetadata", reference.safeMetadata());
        return canonicalHash(canonical);
    }

    private byte[] canonicalBytes(Object value) {
        try {
            return canonicalMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                "Action proposal payload is not serializable",
                ex
            );
        }
    }

    private byte[] requireBinding(String binding) {
        if (binding == null || binding.isBlank()) {
            throw new IllegalArgumentException("payload binding is required");
        }
        return binding.trim().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] secret(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.length() < 32) {
            throw new IllegalArgumentException(
                field + " must contain at least 32 characters"
            );
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? "<none>" : value.trim();
    }
}
