package com.ai.fabric.realapps.tenantportal.service;

import ai.fabric.intent.action.ActionAccessMode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantKnowledgeService {

    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();

    public TenantKnowledgeService() {
        seed(new KnowledgeDocument("doc-a", "tenant-a", "VPN setup", "Tenant A VPN requires Okta enrollment.", "internal"));
        seed(new KnowledgeDocument("doc-b", "tenant-b", "VPN setup", "Tenant B VPN requires hardware key enrollment.", "internal"));
        seed(new KnowledgeDocument("doc-admin", "tenant-a", "Admin policy", "Admin-only billing export policy.", "restricted"));
    }

    public KnowledgeDocument seed(KnowledgeDocument document) {
        KnowledgeDocument normalized = normalize(document);
        documents.put(normalized.id(), normalized);
        return normalized;
    }

    public List<KnowledgeHit> search(UserContext context, String query) {
        UserContext user = requireUser(context);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return documents.values().stream()
            .filter(document -> canRead(user, document))
            .filter(document -> !StringUtils.hasText(normalizedQuery)
                || document.title().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || document.content().toLowerCase(Locale.ROOT).contains(normalizedQuery))
            .sorted(Comparator.comparing(KnowledgeDocument::id))
            .map(document -> new KnowledgeHit(document.id(), document.title(), document.tenantId(), metadata(document)))
            .toList();
    }

    public CatalogSummary catalog(UserContext context) {
        UserContext user = requireUser(context);
        List<KnowledgeDocument> visible = documents.values().stream()
            .filter(document -> "ADMIN".equals(user.role()) || user.tenantId().equals(document.tenantId()))
            .sorted(Comparator.comparing(KnowledgeDocument::id))
            .toList();
        return new CatalogSummary(user.role(), visible.size(), visible.stream()
            .map(document -> new CatalogEntry(document.id(), document.tenantId(), document.title(), metadata(document)))
            .toList());
    }

    public ActionDecision executeAction(UserContext context, TenantActionRequest request) {
        UserContext user = requireUser(context);
        TenantActionRequest effective = request != null
            ? request
            : new TenantActionRequest(null, null, ActionAccessMode.READ, false);
        KnowledgeDocument document = documents.get(requireText(effective.documentId(), "documentId"));
        if (document == null) {
            return ActionDecision.failure("TARGET_NOT_FOUND", "Document does not exist.");
        }
        if (!"ADMIN".equals(user.role()) && !user.tenantId().equals(document.tenantId())) {
            return ActionDecision.failure("CROSS_TENANT_DENIED", "Action target belongs to a different tenant.");
        }
        ActionAccessMode mode = effective.accessMode() != null ? effective.accessMode() : ActionAccessMode.READ;
        if (!mode.isReadOnly() && !"ADMIN".equals(user.role())) {
            return ActionDecision.failure("ROLE_DENIED", "Only tenant admins can execute write actions.");
        }
        if (!mode.isReadOnly() && !effective.confirmed()) {
            return new ActionDecision(false, true, "Confirm " + effective.actionId(), null, Map.of("documentId", document.id()));
        }
        return new ActionDecision(true, false, "Action executed", null, Map.of(
            "actionId", effective.actionId(),
            "documentId", document.id(),
            "tenantId", document.tenantId()
        ));
    }

    public TenantDeletionResult deleteTenant(UserContext context, String tenantId) {
        UserContext user = requireUser(context);
        if (!"ADMIN".equals(user.role())) {
            return new TenantDeletionResult(false, "ROLE_DENIED", 0, List.of());
        }
        String tenant = requireText(tenantId, "tenantId");
        List<String> deletedIds = documents.values().stream()
            .filter(document -> tenant.equals(document.tenantId()))
            .map(KnowledgeDocument::id)
            .sorted()
            .toList();
        deletedIds.forEach(documents::remove);
        return new TenantDeletionResult(true, null, deletedIds.size(), deletedIds);
    }

    private boolean canRead(UserContext user, KnowledgeDocument document) {
        if ("ADMIN".equals(user.role())) {
            return true;
        }
        return user.tenantId().equals(document.tenantId()) && !"restricted".equals(document.visibility());
    }

    private Map<String, Object> metadata(KnowledgeDocument document) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", document.tenantId());
        metadata.put("visibility", document.visibility());
        metadata.put("entityType", "tenant-document");
        return Map.copyOf(metadata);
    }

    private KnowledgeDocument normalize(KnowledgeDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        return new KnowledgeDocument(
            requireText(document.id(), "id"),
            requireText(document.tenantId(), "tenantId"),
            requireText(document.title(), "title"),
            requireText(document.content(), "content"),
            StringUtils.hasText(document.visibility()) ? document.visibility().trim() : "internal"
        );
    }

    private UserContext requireUser(UserContext context) {
        if (context == null) {
            throw new IllegalArgumentException("user context is required");
        }
        return new UserContext(
            requireText(context.tenantId(), "tenantId"),
            requireText(context.role(), "role").toUpperCase(Locale.ROOT)
        );
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    public record UserContext(String tenantId, String role) {}

    public record KnowledgeDocument(String id, String tenantId, String title, String content, String visibility) {}

    public record KnowledgeHit(String id, String title, String tenantId, Map<String, Object> metadata) {}

    public record CatalogEntry(String id, String tenantId, String title, Map<String, Object> metadata) {}

    public record CatalogSummary(String role, int visibleDocuments, List<CatalogEntry> entries) {}

    public record TenantActionRequest(String actionId, String documentId, ActionAccessMode accessMode, boolean confirmed) {}

    public record ActionDecision(
        boolean success,
        boolean confirmationRequired,
        String message,
        String errorCode,
        Map<String, Object> data
    ) {
        static ActionDecision failure(String errorCode, String message) {
            return new ActionDecision(false, false, message, errorCode, Map.of());
        }
    }

    public record TenantDeletionResult(boolean success, String errorCode, int deletedDocuments, List<String> deletedIds) {}
}
