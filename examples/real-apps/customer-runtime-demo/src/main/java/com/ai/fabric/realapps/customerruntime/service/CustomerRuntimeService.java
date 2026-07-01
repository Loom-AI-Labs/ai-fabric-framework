package com.ai.fabric.realapps.customerruntime.service;

import ai.fabric.datasync.dto.DataSyncDeleteRequest;
import ai.fabric.datasync.dto.DataSyncIdentity;
import ai.fabric.datasync.dto.DataSyncTrace;
import ai.fabric.datasync.dto.DataSyncUpsertRequest;
import ai.fabric.intent.action.ActionAccessMode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CustomerRuntimeService {

    private static final String VECTOR_SPACE = "customer-record";

    private final Map<String, DomainRecord> indexedRecords = new ConcurrentHashMap<>();
    private volatile boolean connectorAvailable = true;

    public SyncEvidence upsertDomainRecord(DomainRecord record) {
        DomainRecord normalized = normalize(record);
        indexedRecords.put(normalized.id(), normalized);

        DataSyncUpsertRequest upsert = new DataSyncUpsertRequest(
            VECTOR_SPACE,
            normalized.id(),
            normalized.content(),
            normalized.entity(),
            normalized.metadata(),
            identity(normalized),
            trace("customer-domain-fixture", normalized.id())
        );
        return new SyncEvidence("UPSERT", upsert, null, List.copyOf(indexedRecords.keySet()));
    }

    public SyncEvidence deleteDomainRecord(String id) {
        String safeId = requireText(id, "id");
        indexedRecords.remove(safeId);
        DataSyncDeleteRequest delete = new DataSyncDeleteRequest(
            VECTOR_SPACE,
            safeId,
            new DataSyncIdentity(safeId, "deleted", "record", 1, null),
            trace("customer-domain-fixture", safeId)
        );
        return new SyncEvidence("DELETE", null, delete, List.copyOf(indexedRecords.keySet()));
    }

    public List<SearchHit> search(String tenantId, String query) {
        String tenant = requireText(tenantId, "tenantId");
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return indexedRecords.values().stream()
            .filter(record -> tenant.equals(record.tenantId()))
            .filter(record -> !record.deleted())
            .filter(record -> !StringUtils.hasText(normalizedQuery)
                || record.content().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || record.title().toLowerCase(Locale.ROOT).contains(normalizedQuery))
            .sorted(Comparator.comparing(DomainRecord::id))
            .map(record -> new SearchHit(record.id(), record.title(), record.metadata()))
            .toList();
    }

    public ActionOutcome executeAction(ActionRequest request) {
        ActionRequest effective = request != null ? request : new ActionRequest(null, null, null, null, false);
        String actionId = requireText(effective.actionId(), "actionId");
        String recordId = requireText(effective.recordId(), "recordId");
        ActionAccessMode mode = effective.accessMode() != null ? effective.accessMode() : ActionAccessMode.READ;

        if (!connectorAvailable) {
            return ActionOutcome.failure(actionId, "CONNECTOR_UNAVAILABLE", "Customer connector is unavailable.");
        }
        DomainRecord record = indexedRecords.get(recordId);
        if (record == null || record.deleted()) {
            return ActionOutcome.failure(actionId, "TARGET_NOT_FOUND", "Customer record is not available.");
        }
        if (!mode.isReadOnly() && !effective.confirmed()) {
            return new ActionOutcome(
                actionId,
                false,
                true,
                "Confirm " + actionId + " for record " + recordId,
                null,
                Map.of("accessMode", mode.name(), "recordId", recordId)
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("tenantId", record.tenantId());
        result.put("actionId", actionId);
        result.put("executedAt", Instant.now().toString());
        result.put("params", effective.params() == null ? Map.of() : Map.copyOf(effective.params()));
        return new ActionOutcome(actionId, true, false, "Action executed", null, result);
    }

    public void setConnectorAvailable(boolean connectorAvailable) {
        this.connectorAvailable = connectorAvailable;
    }

    private DomainRecord normalize(DomainRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        String id = requireText(record.id(), "id");
        String tenantId = requireText(record.tenantId(), "tenantId");
        String title = requireText(record.title(), "title");
        String content = requireText(record.content(), "content");
        String version = StringUtils.hasText(record.version()) ? record.version().trim() : "1";
        return new DomainRecord(id, tenantId, title, content, version, false);
    }

    private DataSyncIdentity identity(DomainRecord record) {
        return new DataSyncIdentity(
            record.id(),
            record.version(),
            "record",
            1,
            sha256(record.content())
        );
    }

    private DataSyncTrace trace(String source, String recordId) {
        return new DataSyncTrace(
            "trace-" + recordId,
            Map.of("source", source, "recordId", recordId)
        );
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    public record DomainRecord(
        String id,
        String tenantId,
        String title,
        String content,
        String version,
        boolean deleted
    ) {
        Map<String, Object> entity() {
            return Map.of("title", title, "content", content, "tenantId", tenantId, "version", version);
        }

        Map<String, Object> metadata() {
            return Map.of("tenantId", tenantId, "sourceRecordId", id, "sourceVersion", version);
        }
    }

    public record SyncEvidence(
        String operation,
        DataSyncUpsertRequest upsertRequest,
        DataSyncDeleteRequest deleteRequest,
        List<String> currentIndexedRecordIds
    ) {}

    public record SearchHit(String id, String title, Map<String, Object> metadata) {}

    public record ActionRequest(
        String actionId,
        String recordId,
        ActionAccessMode accessMode,
        Map<String, Object> params,
        boolean confirmed
    ) {}

    public record ActionOutcome(
        String actionId,
        boolean success,
        boolean confirmationRequired,
        String message,
        String errorCode,
        Map<String, Object> data
    ) {
        static ActionOutcome failure(String actionId, String errorCode, String message) {
            return new ActionOutcome(actionId, false, false, message, errorCode, Map.of());
        }
    }
}
