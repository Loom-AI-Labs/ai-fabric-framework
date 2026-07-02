package com.ai.fabric.realapps.tenantportal.service;

import ai.fabric.intent.action.ActionAccessMode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantKnowledgeService {

    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();

    public TenantKnowledgeService() {
        resetDemoData();
    }

    public synchronized TenantGuardDashboard resetDemoData() {
        documents.clear();
        seed(new KnowledgeDocument(
            "doc-a",
            "tenant-a",
            "VPN setup",
            "Tenant A VPN requires Okta enrollment, device compliance, and a region-specific split tunnel profile.",
            "internal"
        ));
        seed(new KnowledgeDocument(
            "doc-a-billing",
            "tenant-a",
            "Billing export",
            "Tenant A finance admins can export invoices after SSO step-up approval.",
            "internal"
        ));
        seed(new KnowledgeDocument(
            "doc-admin",
            "tenant-a",
            "Admin policy",
            "Admin-only billing export policy with restricted audit notes.",
            "restricted"
        ));
        seed(new KnowledgeDocument(
            "doc-b",
            "tenant-b",
            "VPN setup",
            "Tenant B VPN requires hardware key enrollment and a separate privileged access group.",
            "internal"
        ));
        seed(new KnowledgeDocument(
            "doc-b-keys",
            "tenant-b",
            "Hardware key rotation",
            "Tenant B rotates hardware keys every 90 days and blocks stale authenticators.",
            "internal"
        ));
        seed(new KnowledgeDocument(
            "doc-platform",
            "platform",
            "Platform retention",
            "Platform operators can inspect tenant deletion evidence but cannot leak cross-tenant content.",
            "restricted"
        ));
        return dashboard();
    }

    public TenantGuardDashboard dashboard() {
        return new TenantGuardDashboard(
            scenarios(),
            documentStats(),
            compareSearch("VPN"),
            catalog(new UserContext("tenant-a", "USER")),
            catalog(new UserContext("platform", "ADMIN")),
            executeAction(
                new UserContext("tenant-a", "USER"),
                new TenantActionRequest("archive_document", "doc-b", ActionAccessMode.WRITE_ONLY, true)
            ),
            executeAction(
                new UserContext("tenant-a", "ADMIN"),
                new TenantActionRequest("archive_document", "doc-a", ActionAccessMode.WRITE_ONLY, false)
            ),
            deletionPreview("tenant-b")
        );
    }

    public SearchComparison compareSearch(String query) {
        String effectiveQuery = StringUtils.hasText(query) ? query.trim() : "VPN";
        return new SearchComparison(
            effectiveQuery,
            search(new UserContext("tenant-a", "USER"), effectiveQuery),
            search(new UserContext("tenant-b", "USER"), effectiveQuery),
            search(new UserContext("platform", "ADMIN"), effectiveQuery)
        );
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
            .filter(document -> isPlatformAdmin(user) || canRead(user, document))
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
        if (!canTarget(user, document)) {
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
        if (!isPlatformAdmin(user) && !user.tenantId().equals(tenant)) {
            return new TenantDeletionResult(false, "CROSS_TENANT_DENIED", 0, List.of());
        }
        List<String> deletedIds = documents.values().stream()
            .filter(document -> tenant.equals(document.tenantId()))
            .map(KnowledgeDocument::id)
            .sorted()
            .toList();
        deletedIds.forEach(documents::remove);
        return new TenantDeletionResult(true, null, deletedIds.size(), deletedIds);
    }

    private List<TenantGuardScenario> scenarios() {
        return List.of(
            new TenantGuardScenario(
                "tenant-a-vpn",
                "tenant-a",
                "Acme Finance",
                "USER",
                "VPN",
                "Ask the same VPN question as tenant A and verify only tenant A evidence appears.",
                "Returns Okta/device-compliance guidance, never tenant B hardware-key details."
            ),
            new TenantGuardScenario(
                "tenant-b-vpn",
                "tenant-b",
                "Northstar Health",
                "USER",
                "VPN",
                "Ask the same VPN question as tenant B and verify tenant B gets its own policy.",
                "Returns hardware-key guidance, never tenant A Okta policy."
            ),
            new TenantGuardScenario(
                "platform-admin",
                "platform",
                "Platform Operations",
                "ADMIN",
                "VPN",
                "Inspect admin catalog visibility and deletion evidence across tenants.",
                "Admin sees cross-tenant catalog evidence while guarded actions still require policy checks."
            )
        );
    }

    private DocumentStats documentStats() {
        Set<String> tenants = documents.values().stream()
            .map(KnowledgeDocument::tenantId)
            .collect(java.util.stream.Collectors.toSet());
        long restricted = documents.values().stream()
            .filter(document -> "restricted".equals(document.visibility()))
            .count();
        long tenantADocs = documents.values().stream()
            .filter(document -> "tenant-a".equals(document.tenantId()))
            .count();
        long tenantBDocs = documents.values().stream()
            .filter(document -> "tenant-b".equals(document.tenantId()))
            .count();
        return new DocumentStats(documents.size(), tenants.size(), restricted, tenantADocs, tenantBDocs);
    }

    private TenantDeletionPreview deletionPreview(String tenantId) {
        List<String> ids = documents.values().stream()
            .filter(document -> tenantId.equals(document.tenantId()))
            .map(KnowledgeDocument::id)
            .sorted()
            .toList();
        return new TenantDeletionPreview(tenantId, ids.size(), ids);
    }

    private boolean canRead(UserContext user, KnowledgeDocument document) {
        if (isPlatformAdmin(user)) {
            return true;
        }
        return user.tenantId().equals(document.tenantId()) && !"restricted".equals(document.visibility());
    }

    private boolean canTarget(UserContext user, KnowledgeDocument document) {
        return isPlatformAdmin(user) || user.tenantId().equals(document.tenantId());
    }

    private boolean isPlatformAdmin(UserContext user) {
        return "ADMIN".equals(user.role()) && "platform".equals(user.tenantId());
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

    public record TenantGuardScenario(
        String id,
        String tenantId,
        String tenantName,
        String role,
        String defaultQuery,
        String operatorGoal,
        String expectedEvidence
    ) {}

    public record DocumentStats(
        int totalDocuments,
        int tenantCount,
        long restrictedDocuments,
        long tenantADocuments,
        long tenantBDocuments
    ) {}

    public record SearchComparison(
        String query,
        List<KnowledgeHit> tenantAResults,
        List<KnowledgeHit> tenantBResults,
        List<KnowledgeHit> platformAdminResults
    ) {}

    public record TenantDeletionPreview(String targetTenantId, int matchingDocuments, List<String> documentIds) {}

    public record TenantGuardDashboard(
        List<TenantGuardScenario> scenarios,
        DocumentStats stats,
        SearchComparison defaultComparison,
        CatalogSummary tenantUserCatalog,
        CatalogSummary platformAdminCatalog,
        ActionDecision crossTenantDenied,
        ActionDecision writeActionPreview,
        TenantDeletionPreview deletionPreview
    ) {}

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
