package ai.fabric.governance.catalog.jpa;

import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class JpaIndexCatalog implements IndexCatalog {

    private final IndexCatalogRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public void upsert(IndexCatalogEntry entry) {
        if (entry == null || entry.getEntityType() == null || entry.getEntityId() == null) {
            return;
        }
        String key = IndexCatalogEntity.buildKey(entry.getEntityType(), entry.getEntityId());
        IndexCatalogEntity entity = repository.findById(key).orElseGet(IndexCatalogEntity::new);
        entity.setKey(key);
        entity.setEntityType(entry.getEntityType());
        entity.setEntityId(entry.getEntityId());
        entity.setVectorId(entry.getVectorId());

        LocalDateTime incomingCreatedAt = entry.getIndexedCreatedAt();
        LocalDateTime incomingUpdatedAt = entry.getIndexedUpdatedAt() != null ? entry.getIndexedUpdatedAt() : LocalDateTime.now();

        if (entity.getIndexedCreatedAt() == null) {
            entity.setIndexedCreatedAt(incomingCreatedAt != null ? incomingCreatedAt : incomingUpdatedAt);
        }
        entity.setIndexedUpdatedAt(incomingUpdatedAt);
        entity.setMetadataJson(serialize(entry.getMetadata()));
        repository.save(entity);
    }

    @Override
    public void delete(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return;
        }
        repository.deleteByEntityTypeAndEntityId(entityType, entityId);
    }

    @Override
    public void deleteByEntityType(String entityType) {
        if (entityType == null) {
            return;
        }
        repository.deleteByEntityType(entityType);
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }

    @Override
    public boolean exists(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return false;
        }
        return repository.existsByEntityTypeAndEntityId(entityType, entityId);
    }

    @Override
    public IndexCatalogScanPage scan(IndexCatalogScanRequest request) {
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            return IndexCatalogScanPage.builder()
                .entries(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        int limit = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 200;
        LocalDateTime updatedAfter = decodeUpdatedAfterCursor(request.getCursor());
        Map<String, Object> metadataEquals = normalizeMetadataEquals(request.getMetadataEquals());

        List<IndexCatalogEntity> entities = repository.scanByEntityType(request.getEntityType(), updatedAfter, PageRequest.of(0, limit + 1));
        if (entities == null || entities.isEmpty()) {
            return IndexCatalogScanPage.builder()
                .entries(List.of())
                .hasMore(false)
                .nextCursor(null)
                .build();
        }

        boolean hasMore = entities.size() > limit;
        List<IndexCatalogEntity> pageEntities = hasMore ? entities.subList(0, limit) : entities;

        List<IndexCatalogEntry> entries = pageEntities.stream()
            .map(this::toEntry)
            .filter(entry -> metadataMatches(entry.getMetadata(), metadataEquals))
            .toList();

        String nextCursor = null;
        if (hasMore) {
            IndexCatalogEntity last = pageEntities.get(pageEntities.size() - 1);
            nextCursor = encodeUpdatedAfterCursor(last.getIndexedUpdatedAt());
        }

        return IndexCatalogScanPage.builder()
            .entries(entries)
            .hasMore(hasMore)
            .nextCursor(nextCursor)
            .build();
    }

    @Override
    public List<IndexCatalogEntry> findByMetadataContainingSnippet(String snippet, int limit) {
        if (snippet == null || snippet.isBlank()) {
            return List.of();
        }
        int resolvedLimit = limit > 0 ? limit : 200;
        List<IndexCatalogEntity> entities = repository.findByMetadataSnippet(snippet, PageRequest.of(0, resolvedLimit));
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
            .map(this::toEntry)
            .toList();
    }

    private IndexCatalogEntry toEntry(IndexCatalogEntity entity) {
        return IndexCatalogEntry.builder()
            .entityType(entity.getEntityType())
            .entityId(entity.getEntityId())
            .vectorId(entity.getVectorId())
            .indexedCreatedAt(entity.getIndexedCreatedAt())
            .indexedUpdatedAt(entity.getIndexedUpdatedAt())
            .metadata(deserialize(entity.getMetadataJson()))
            .build();
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.debug("Failed to serialize index catalog metadata", ex);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(raw, java.util.Map.class);
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private static String encodeUpdatedAfterCursor(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return null;
        }
        String raw = "updatedAfter:" + updatedAt.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDateTime decodeUpdatedAfterCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.startsWith("updatedAfter:")) {
                return null;
            }
            String ts = raw.substring("updatedAfter:".length());
            return LocalDateTime.parse(ts);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> normalizeMetadataEquals(Map<String, Object> metadataEquals) {
        if (metadataEquals == null || metadataEquals.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        metadataEquals.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                normalized.put(key, value);
            }
        });
        return Collections.unmodifiableMap(normalized);
    }

    private boolean metadataMatches(Map<String, Object> metadata, Map<String, Object> metadataEquals) {
        if (metadataEquals == null || metadataEquals.isEmpty()) {
            return true;
        }
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return metadataEquals.entrySet().stream()
            .allMatch(entry -> metadataValueMatches(metadata.get(entry.getKey()), entry.getValue()));
    }

    private boolean metadataValueMatches(Object actual, Object expected) {
        if (Objects.equals(actual, expected)) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        return actual.toString().equals(expected.toString());
    }
}
