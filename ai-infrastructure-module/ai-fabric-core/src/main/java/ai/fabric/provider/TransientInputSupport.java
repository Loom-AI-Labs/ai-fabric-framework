package ai.fabric.provider;

import ai.fabric.dto.AIGenerationInputPart;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.http.HttpClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared provider helpers for transient provider-native inputs.
 */
public final class TransientInputSupport {

    public static final String STATUS_UNSUPPORTED = "PROVIDER_FILE_URL_INPUT_UNSUPPORTED";
    public static final String STATUS_NOT_USED = "NOT_USED";
    private static final int DEFAULT_MAX_FETCH_BYTES = 20 * 1024 * 1024;

    private TransientInputSupport() {
    }

    public static boolean hasFileUrlInputs(AIGenerationRequest request) {
        return !fileUrlInputParts(request).isEmpty();
    }

    public static List<AIGenerationInputPart> fileUrlInputParts(AIGenerationRequest request) {
        if (request == null || request.getInputParts() == null || request.getInputParts().isEmpty()) {
            return List.of();
        }
        List<AIGenerationInputPart> parts = new ArrayList<>();
        for (AIGenerationInputPart part : request.getInputParts()) {
            if (part != null && part.isFileUrl()) {
                parts.add(part);
            }
        }
        return List.copyOf(parts);
    }

    public static AIGenerationResponse unsupportedFileUrlResponse(AIGenerationRequest request,
                                                                  String providerName,
                                                                  String reason) {
        List<AIGenerationInputPart> fileParts = fileUrlInputParts(request);
        List<Map<String, Object>> documentUsage = new ArrayList<>();
        for (AIGenerationInputPart part : fileParts) {
            documentUsage.add(notUsedDocumentUsage(part, providerName, reason));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", "I could not analyze the supplied temporary document with the configured AI provider.");
        payload.put("documentUsage", documentUsage);
        payload.put("reason", reason);
        payload.put("provider", providerName);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transientInputs", redactedDescriptors(fileParts));
        metadata.put("documentUsage", documentUsage);
        metadata.put("provider", providerName);
        metadata.put("status", STATUS_UNSUPPORTED);

        return AIGenerationResponse.builder()
            .content(toJsonLike(payload))
            .model(request != null ? request.getModel() : null)
            .requestId(UUID.randomUUID().toString())
            .processingTimeMs(0L)
            .metadata(Map.copyOf(metadata))
            .generatedAt(LocalDateTime.now())
            .status(STATUS_UNSUPPORTED)
            .error(reason)
            .build();
    }

    public static List<Map<String, Object>> redactedDescriptors(List<AIGenerationInputPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> descriptors = new ArrayList<>();
        for (AIGenerationInputPart part : parts) {
            if (part != null) {
                descriptors.add(part.redactedDescriptor());
            }
        }
        return List.copyOf(descriptors);
    }

    public static Map<String, Object> notUsedDocumentUsage(AIGenerationInputPart part,
                                                           String providerName,
                                                           String reason) {
        Map<String, Object> usage = new LinkedHashMap<>();
        putText(usage, "documentId", part.getDocumentId());
        putText(usage, "fileName", part.getFileName());
        putText(usage, "contentType", part.getContentType());
        usage.put("status", STATUS_NOT_USED);
        usage.put("accessMethod", "NONE");
        usage.put("evidence", List.of());
        usage.put("reason", reason);
        usage.put("provider", providerName);
        return Collections.unmodifiableMap(usage);
    }

    public static FetchedTransientFile fetchTransientFile(HttpClient httpClient,
                                                          AIGenerationInputPart part,
                                                          int maxBytes) {
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient is required");
        }
        if (part == null || !part.isFileUrl()) {
            throw new IllegalArgumentException("FILE_URL input part is required");
        }
        validateSafeHttpsUrl(part.getUrl());
        int effectiveMaxBytes = maxBytes > 0 ? maxBytes : DEFAULT_MAX_FETCH_BYTES;
        if (part.getSizeBytes() != null && part.getSizeBytes() > effectiveMaxBytes) {
            throw new IllegalArgumentException("Transient file exceeds provider fetch size limit");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "*/*");
        ResponseEntity<byte[]> response =
            httpClient.exchange(part.getUrl(), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        byte[] body = response != null ? response.getBody() : null;
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("Transient file response was empty");
        }
        if (body.length > effectiveMaxBytes) {
            throw new IllegalArgumentException("Transient file response exceeds provider fetch size limit");
        }

        String responseContentType = null;
        if (response.getHeaders() != null && response.getHeaders().getContentType() != null) {
            responseContentType = response.getHeaders().getContentType().toString();
        }
        String contentType = firstText(part.getContentType(), responseContentType, "application/octet-stream");
        return new FetchedTransientFile(part, body, normalizeContentType(contentType));
    }

    public static void validateFileUrlInput(AIGenerationInputPart part) {
        if (part == null || !part.isFileUrl()) {
            throw new IllegalArgumentException("FILE_URL input part is required");
        }
        validateSafeHttpsUrl(part.getUrl());
    }

    public static boolean isTextLikeContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        return normalized.startsWith("text/")
            || normalized.equals("application/json")
            || normalized.equals("application/ld+json")
            || normalized.equals("application/xml")
            || normalized.equals("application/xhtml+xml")
            || normalized.equals("application/yaml")
            || normalized.equals("application/x-yaml")
            || normalized.equals("application/csv")
            || normalized.equals("application/markdown");
    }

    public static boolean isPdfContentType(String contentType) {
        return "application/pdf".equals(normalizeContentType(contentType));
    }

    public static boolean isImageContentType(String contentType) {
        return normalizeContentType(contentType).startsWith("image/");
    }

    public static boolean isProviderVisionImageContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        return normalized.equals("image/jpeg")
            || normalized.equals("image/jpg")
            || normalized.equals("image/png")
            || normalized.equals("image/webp")
            || normalized.equals("image/gif");
    }

    public static boolean isAudioContentType(String contentType) {
        return normalizeContentType(contentType).startsWith("audio/");
    }

    public static boolean isVideoContentType(String contentType) {
        return normalizeContentType(contentType).startsWith("video/");
    }

    public static boolean isOfficeDocumentContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        return normalized.equals("application/msword")
            || normalized.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            || normalized.equals("application/vnd.ms-excel")
            || normalized.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            || normalized.equals("application/vnd.ms-powerpoint")
            || normalized.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    public static boolean isOpenAiResponsesFileUrlContentType(String contentType) {
        String normalized = normalizeContentType(contentType);
        return isPdfContentType(normalized)
            || isTextLikeContentType(normalized)
            || isOfficeDocumentContentType(normalized);
    }

    public static boolean isOpenAiResponsesImageUrlContentType(String contentType) {
        return isProviderVisionImageContentType(contentType);
    }

    public static boolean isAzureResponsesImageUrlContentType(String contentType) {
        return isProviderVisionImageContentType(contentType);
    }

    public static boolean isAnthropicUrlContentType(String contentType) {
        return isPdfContentType(contentType) || isProviderVisionImageContentType(contentType);
    }

    public static boolean isGeminiInlineContentType(String contentType) {
        return isPdfContentType(contentType)
            || isTextLikeContentType(contentType)
            || isProviderVisionImageContentType(contentType)
            || isAudioContentType(contentType)
            || isVideoContentType(contentType);
    }

    public static boolean requiresDocumentUsage(AIGenerationRequest request) {
        return request != null
            && hasFileUrlInputs(request)
            && (request.getTransientInputPolicy() == null || request.getTransientInputPolicy().isRequireDocumentUsage());
    }

    public static boolean hasDocumentUsage(AIGenerationResponse response) {
        if (response == null) {
            return false;
        }
        if (response.getMetadata() != null && response.getMetadata().containsKey("documentUsage")) {
            return true;
        }
        String content = response.getContent();
        return content != null && content.contains("\"documentUsage\"");
    }

    public static String dataUri(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes are required");
        }
        return "data:" + normalizeContentType(contentType) + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    public static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        String value = contentType.trim().toLowerCase();
        int semicolon = value.indexOf(';');
        return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
    }

    public static String decodeUtf8Text(FetchedTransientFile fetchedFile, int maxChars) {
        if (fetchedFile == null || fetchedFile.bytes() == null) {
            return "";
        }
        String text = new String(fetchedFile.bytes(), StandardCharsets.UTF_8);
        if (fetchedFile.isHtml()) {
            text = stripBasicHtml(text);
        }
        text = text.replace('\u0000', ' ').trim();
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars);
        }
        return text;
    }

    private static void validateSafeHttpsUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Transient file URL is invalid", ex);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Transient file URL must use HTTPS");
        }
        if (StringUtils.hasText(uri.getUserInfo())) {
            throw new IllegalArgumentException("Transient file URL must not include userinfo");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("Transient file URL host is required");
        }
        String normalizedHost = host.trim().toLowerCase();
        if (normalizedHost.equals("localhost")
            || normalizedHost.endsWith(".localhost")
            || normalizedHost.endsWith(".local")
            || normalizedHost.equals("metadata.google.internal")
            || normalizedHost.equals("169.254.169.254")
            || normalizedHost.equals("0.0.0.0")
            || normalizedHost.equals("127.0.0.1")
            || normalizedHost.startsWith("10.")
            || normalizedHost.startsWith("192.168.")
            || isPrivate172(normalizedHost)) {
            throw new IllegalArgumentException("Transient file URL host is not allowed");
        }
    }

    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String stripBasicHtml(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String withoutScripts = value
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        return withoutScripts
            .replaceAll("(?is)<[^>]+>", " ")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public record FetchedTransientFile(AIGenerationInputPart source, byte[] bytes, String contentType) {

        public boolean isPdf() {
            return "application/pdf".equals(contentType);
        }

        public boolean isImage() {
            return contentType != null && contentType.startsWith("image/");
        }

        public boolean isHtml() {
            return "text/html".equals(contentType) || "application/xhtml+xml".equals(contentType);
        }
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private static String toJsonLike(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append('"').append(':');
            appendJsonValue(sb, entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                appendJsonValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
