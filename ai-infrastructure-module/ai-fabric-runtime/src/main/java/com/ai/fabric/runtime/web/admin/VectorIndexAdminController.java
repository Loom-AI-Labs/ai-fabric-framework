package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.dto.VectorScanPage;
import com.ai.infrastructure.dto.VectorScanRequest;
import com.ai.infrastructure.rag.VectorDatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/indexing")
@RequiredArgsConstructor
public class VectorIndexAdminController {

    private final VectorDatabaseService vectorDatabaseService;
    private final AIEntityConfigurationLoader entityConfigurationLoader;
    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;

    /**
     * Lightweight status endpoint so you can confirm indexing is happening.
     */
    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest httpRequest) {
        authorize(httpRequest, RuntimeAdminScopeCatalog.RUNTIME_INDEXING_OVERVIEW, "/api/admin/indexing/overview");

        Set<String> entityTypes = entityConfigurationLoader != null
            ? entityConfigurationLoader.getSupportedEntityTypes()
            : Set.of();

        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (String entityType : entityTypes) {
            if (!StringUtils.hasText(entityType)) {
                continue;
            }
            long count = vectorDatabaseService.getVectorCountByEntityType(entityType);
            counts.put(entityType, count);
            total += count;
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "vectorDb", vectorDatabaseService.getClass().getSimpleName(),
            "supportsVectorScan", vectorDatabaseService.supportsVectorScan(),
            "entityTypes", entityTypes,
            "countsByEntityType", counts,
            "totalVectors", total
        ));
    }

    /**
     * Paged inspection endpoint.
     *
     * <p>Supports offset-based paging (as requested) and also returns/accepts the framework cursor.</p>
     */
    @GetMapping("/vectors")
    public ResponseEntity<?> vectors(@RequestParam("entityType") String entityType,
                                     @RequestParam(value = "offset", required = false) Integer offset,
                                     @RequestParam(value = "limit", required = false) Integer limit,
                                     @RequestParam(value = "cursor", required = false) String cursor,
                                     @RequestParam(value = "includeContent", required = false) Boolean includeContent,
                                     @RequestParam(value = "includeEmbedding", required = false) Boolean includeEmbedding,
                                     @RequestParam(value = "includeMetadata", required = false) Boolean includeMetadata,
                                     HttpServletRequest httpRequest) {
        authorize(httpRequest, RuntimeAdminScopeCatalog.RUNTIME_INDEXING_VECTORS, "/api/admin/indexing/vectors");
        if (!StringUtils.hasText(entityType)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", "entityType is required"
            ));
        }

        Integer safeLimit = limit != null ? Math.max(1, Math.min(limit, 500)) : 200;
        String effectiveCursor = StringUtils.hasText(cursor) ? cursor : null;
        if (effectiveCursor == null && offset != null && offset >= 0) {
            effectiveCursor = encodeOffsetCursor(offset);
        }

        VectorScanRequest req = VectorScanRequest.builder()
            .entityType(entityType)
            .limit(safeLimit)
            .cursor(effectiveCursor)
            .includeContent(includeContent == null || includeContent)
            .includeEmbedding(includeEmbedding != null && includeEmbedding)
            .includeMetadata(includeMetadata == null || includeMetadata)
            .build();

        VectorScanPage page = vectorDatabaseService.scan(req);
        Integer nextOffset = decodeOffsetCursor(page != null ? page.getNextCursor() : null);
        int resolvedOffset = offset != null ? offset : (effectiveCursor != null ? decodeOffsetCursor(effectiveCursor) : 0);

        // Use a mutable map so we can safely return nulls (Map.of forbids null values).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("entityType", entityType);
        body.put("limit", safeLimit);
        body.put("cursor", effectiveCursor);
        body.put("nextCursor", page != null ? page.getNextCursor() : null);
        body.put("offset", resolvedOffset);
        body.put("nextOffset", nextOffset);
        body.put("hasMore", page != null && page.isHasMore());
        body.put("vectors", page != null && page.getVectors() != null ? page.getVectors() : java.util.List.of());
        return ResponseEntity.ok(body);
    }

    private static String encodeOffsetCursor(int offset) {
        String raw = "offset:" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Integer decodeOffsetCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            if (!raw.startsWith("offset:")) {
                return null;
            }
            return Integer.parseInt(raw.substring("offset:".length()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void authorize(HttpServletRequest request, String scope, String surface) {
        runtimeRequestAuthResolver.requireScope(
            runtimeRequestAuthResolver.resolveVerifiedPrivateContext(request, surface),
            scope,
            surface
        );
    }
}
