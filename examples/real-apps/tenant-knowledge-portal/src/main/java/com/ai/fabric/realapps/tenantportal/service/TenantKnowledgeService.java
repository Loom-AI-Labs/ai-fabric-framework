package com.ai.fabric.realapps.tenantportal.service;

import ai.fabric.intent.action.ActionAccessMode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantKnowledgeService {

    private static final String CANONICAL_SESSION_ID = "canonical";
    private static final Duration DEMO_SESSION_TTL = Duration.ofHours(6);

    private final Map<String, Map<String, KnowledgeDocument>> sessionDocuments = new ConcurrentHashMap<>();
    private final Map<String, Instant> sessionTouchedAt = new ConcurrentHashMap<>();

    public TenantKnowledgeService() {
        resetDemoData();
    }

    public synchronized TenantGuardDashboard resetDemoData() {
        return resetDemoData(null);
    }

    public synchronized TenantGuardDashboard resetDemoData(String sessionId) {
        cleanupExpiredSessions();
        String key = sessionKey(sessionId);
        sessionDocuments.put(key, new ConcurrentHashMap<>(seededDocuments()));
        touch(key);
        return dashboard(sessionId);
    }

    public TenantGuardDashboard dashboard() {
        return dashboard(null);
    }

    public TenantGuardDashboard dashboard(String sessionId) {
        cleanupExpiredSessions();
        SearchComparison comparison = compareSearch(sessionId, "VPN");
        CatalogSummary tenantUserCatalog = catalog(sessionId, new UserContext("tenant-a", "USER"));
        CatalogSummary platformAdminCatalog = catalog(sessionId, new UserContext("platform", "ADMIN"));
        ActionDecision crossTenantDenied = executeAction(
            sessionId,
            new UserContext("tenant-a", "USER"),
            new TenantActionRequest("archive_document", "doc-b", ActionAccessMode.WRITE_ONLY, true)
        );
        ActionDecision writeActionPreview = executeAction(
            sessionId,
            new UserContext("tenant-a", "ADMIN"),
            new TenantActionRequest("archive_document", "doc-a", ActionAccessMode.WRITE_ONLY, false)
        );
        TenantDeletionPreview deletionPreview = deletionPreview(sessionId, "tenant-b");
        return new TenantGuardDashboard(
            scenarios(),
            documentStats(sessionId),
            comparison,
            tenantUserCatalog,
            platformAdminCatalog,
            crossTenantDenied,
            writeActionPreview,
            deletionPreview,
            boundaryProof(comparison, tenantUserCatalog, platformAdminCatalog, crossTenantDenied, writeActionPreview, deletionPreview),
            sessionSummary(sessionId)
        );
    }

    public SearchComparison compareSearch(String query) {
        return compareSearch(null, query);
    }

    public SearchComparison compareSearch(String sessionId, String query) {
        String effectiveQuery = StringUtils.hasText(query) ? query.trim() : "VPN";
        return new SearchComparison(
            effectiveQuery,
            search(sessionId, new UserContext("tenant-a", "USER"), effectiveQuery),
            search(sessionId, new UserContext("tenant-b", "USER"), effectiveQuery),
            search(sessionId, new UserContext("platform", "ADMIN"), effectiveQuery)
        );
    }

    public KnowledgeDocument seed(KnowledgeDocument document) {
        KnowledgeDocument normalized = normalize(document);
        documentsForSession(null).put(normalized.id(), normalized);
        return normalized;
    }

    public List<KnowledgeHit> search(UserContext context, String query) {
        return search(null, context, query);
    }

    public List<KnowledgeHit> search(String sessionId, UserContext context, String query) {
        UserContext user = requireUser(context);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return documentsForSession(sessionId).values().stream()
            .filter(document -> canRead(user, document))
            .filter(document -> !StringUtils.hasText(normalizedQuery)
                || document.title().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || document.content().toLowerCase(Locale.ROOT).contains(normalizedQuery))
            .sorted(Comparator.comparing(KnowledgeDocument::id))
            .map(document -> new KnowledgeHit(document.id(), document.title(), document.tenantId(), metadata(document)))
            .toList();
    }

    public CatalogSummary catalog(UserContext context) {
        return catalog(null, context);
    }

    public CatalogSummary catalog(String sessionId, UserContext context) {
        UserContext user = requireUser(context);
        List<KnowledgeDocument> visible = documentsForSession(sessionId).values().stream()
            .filter(document -> isPlatformAdmin(user) || canRead(user, document))
            .sorted(Comparator.comparing(KnowledgeDocument::id))
            .toList();
        return new CatalogSummary(user.role(), visible.size(), visible.stream()
            .map(document -> new CatalogEntry(document.id(), document.tenantId(), document.title(), metadata(document)))
            .toList());
    }

    public ActionDecision executeAction(UserContext context, TenantActionRequest request) {
        return executeAction(null, context, request);
    }

    public ActionDecision executeAction(String sessionId, UserContext context, TenantActionRequest request) {
        UserContext user = requireUser(context);
        TenantActionRequest effective = request != null
            ? request
            : new TenantActionRequest(null, null, ActionAccessMode.READ, false);
        String actionId = requireText(effective.actionId(), "actionId");
        KnowledgeDocument document = documentsForSession(sessionId).get(requireText(effective.documentId(), "documentId"));
        if (document == null) {
            return ActionDecision.failure(
                "TARGET_NOT_FOUND",
                "Document does not exist.",
                policyData("DENIED", "The requested action target was not found.", user, null, actionId, effective.accessMode())
            );
        }
        if (!canTarget(user, document)) {
            return ActionDecision.failure(
                "CROSS_TENANT_DENIED",
                "Action target belongs to tenant " + document.tenantId() + ", but the caller is scoped to " + user.tenantId() + ".",
                policyData("DENIED", "Cross-tenant write targets are rejected before execution.", user, document, actionId, effective.accessMode())
            );
        }
        ActionAccessMode mode = effective.accessMode() != null ? effective.accessMode() : ActionAccessMode.READ;
        if (!mode.isReadOnly() && !"ADMIN".equals(user.role())) {
            return ActionDecision.failure(
                "ROLE_DENIED",
                "Only tenant admins can execute write actions.",
                policyData("DENIED", "Write actions require an ADMIN role for the caller's tenant.", user, document, actionId, mode)
            );
        }
        if (!mode.isReadOnly() && !effective.confirmed()) {
            return new ActionDecision(
                false,
                true,
                "Confirm " + actionId + " for " + document.title() + " in " + document.tenantId() + ".",
                null,
                policyData("CONFIRMATION_REQUIRED", "Write actions require explicit confirmation after tenant and role checks pass.", user, document, actionId, mode)
            );
        }
        return new ActionDecision(
            true,
            false,
            "Action executed for " + document.title() + " in " + document.tenantId() + " after tenant, role, and confirmation checks.",
            null,
            policyData("APPROVED", "The caller is authorized for this tenant and confirmed the write action.", user, document, actionId, mode)
        );
    }

    public TenantDeletionResult deleteTenant(UserContext context, String tenantId) {
        return deleteTenant(null, context, tenantId);
    }

    public TenantDeletionResult deleteTenant(String sessionId, UserContext context, String tenantId) {
        UserContext user = requireUser(context);
        if (!"ADMIN".equals(user.role())) {
            return new TenantDeletionResult(
                false,
                "ROLE_DENIED",
                0,
                List.of(),
                "Only admins can delete tenant evidence.",
                "DENIED",
                tenantIds(documentsForSession(sessionId))
            );
        }
        String tenant = requireText(tenantId, "tenantId");
        if (!isPlatformAdmin(user) && !user.tenantId().equals(tenant)) {
            return new TenantDeletionResult(
                false,
                "CROSS_TENANT_DENIED",
                0,
                List.of(),
                "Tenant admins can delete only their own tenant evidence.",
                "DENIED",
                tenantIds(documentsForSession(sessionId))
            );
        }
        Map<String, KnowledgeDocument> documents = documentsForSession(sessionId);
        List<String> deletedIds = documents.values().stream()
            .filter(document -> tenant.equals(document.tenantId()))
            .map(KnowledgeDocument::id)
            .sorted()
            .toList();
        deletedIds.forEach(documents::remove);
        return new TenantDeletionResult(
            true,
            null,
            deletedIds.size(),
            deletedIds,
            "Deleted only " + tenant + " evidence. Other tenant documents remain isolated.",
            "APPROVED",
            tenantIds(documents)
        );
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

    private DocumentStats documentStats(String sessionId) {
        Map<String, KnowledgeDocument> documents = documentsForSession(sessionId);
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

    private TenantDeletionPreview deletionPreview(String sessionId, String tenantId) {
        List<String> ids = documentsForSession(sessionId).values().stream()
            .filter(document -> tenantId.equals(document.tenantId()))
            .map(KnowledgeDocument::id)
            .sorted()
            .toList();
        return new TenantDeletionPreview(tenantId, ids.size(), ids);
    }

    private BoundaryProof boundaryProof(
        SearchComparison comparison,
        CatalogSummary tenantUserCatalog,
        CatalogSummary platformAdminCatalog,
        ActionDecision crossTenantDenied,
        ActionDecision writeActionPreview,
        TenantDeletionPreview deletionPreview
    ) {
        List<ProofCheck> checks = List.of(
            new ProofCheck(
                "tenant-a-search-is-scoped",
                "Tenant A search returns only tenant A evidence",
                comparison.tenantAResults().stream().allMatch(hit -> "tenant-a".equals(hit.tenantId())),
                comparison.tenantAResults().stream().map(KnowledgeHit::id).toList().toString()
            ),
            new ProofCheck(
                "tenant-b-search-is-scoped",
                "Tenant B search returns only tenant B evidence",
                comparison.tenantBResults().stream().allMatch(hit -> "tenant-b".equals(hit.tenantId())),
                comparison.tenantBResults().stream().map(KnowledgeHit::id).toList().toString()
            ),
            new ProofCheck(
                "admin-catalog-is-broader",
                "Platform admin catalog sees more evidence than a tenant user",
                platformAdminCatalog.visibleDocuments() > tenantUserCatalog.visibleDocuments(),
                tenantUserCatalog.visibleDocuments() + " tenant docs vs " + platformAdminCatalog.visibleDocuments() + " admin docs"
            ),
            new ProofCheck(
                "cross-tenant-write-denied",
                "Cross-tenant write target is rejected by backend policy",
                "CROSS_TENANT_DENIED".equals(crossTenantDenied.errorCode()),
                String.valueOf(crossTenantDenied.data().get("policyExplanation"))
            ),
            new ProofCheck(
                "same-tenant-write-confirmation",
                "Same-tenant write requires explicit confirmation",
                writeActionPreview.confirmationRequired(),
                String.valueOf(writeActionPreview.data().get("policyExplanation"))
            ),
            new ProofCheck(
                "tenant-delete-is-scoped",
                "Tenant deletion preview contains only tenant B documents",
                deletionPreview.documentIds().stream().allMatch(id -> id.startsWith("doc-b")),
                deletionPreview.documentIds().toString()
            )
        );
        boolean passed = checks.stream().allMatch(ProofCheck::passed);
        return new BoundaryProof(
            passed,
            passed
                ? "All tenant-boundary checks are enforced by the backend."
                : "One or more tenant-boundary checks failed.",
            checks
        );
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

    private Map<String, Object> policyData(
        String policyDecision,
        String policyExplanation,
        UserContext user,
        KnowledgeDocument document,
        String actionId,
        ActionAccessMode accessMode
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyDecision", policyDecision);
        data.put("policyExplanation", policyExplanation);
        data.put("actionId", actionId);
        data.put("subjectTenantId", user.tenantId());
        data.put("subjectRole", user.role());
        data.put("accessMode", accessMode != null ? accessMode.name() : ActionAccessMode.READ.name());
        if (document != null) {
            data.put("documentId", document.id());
            data.put("targetTenantId", document.tenantId());
            data.put("targetVisibility", document.visibility());
        }
        return Map.copyOf(data);
    }

    private Map<String, KnowledgeDocument> documentsForSession(String sessionId) {
        cleanupExpiredSessions();
        String key = sessionKey(sessionId);
        touch(key);
        return sessionDocuments.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>(seededDocuments()));
    }

    private Map<String, KnowledgeDocument> seededDocuments() {
        Map<String, KnowledgeDocument> seeded = new LinkedHashMap<>();
        putSeed(seeded, new KnowledgeDocument(
            "doc-a",
            "tenant-a",
            "VPN setup",
            "Tenant A VPN requires Okta enrollment, device compliance, and a region-specific split tunnel profile.",
            "internal"
        ));
        putSeed(seeded, new KnowledgeDocument(
            "doc-a-billing",
            "tenant-a",
            "Billing export",
            "Tenant A finance admins can export invoices after SSO step-up approval.",
            "internal"
        ));
        putSeed(seeded, new KnowledgeDocument(
            "doc-admin",
            "tenant-a",
            "Admin policy",
            "Admin-only billing export policy with restricted audit notes.",
            "restricted"
        ));
        putSeed(seeded, new KnowledgeDocument(
            "doc-b",
            "tenant-b",
            "VPN setup",
            "Tenant B VPN requires hardware key enrollment and a separate privileged access group.",
            "internal"
        ));
        putSeed(seeded, new KnowledgeDocument(
            "doc-b-keys",
            "tenant-b",
            "Hardware key rotation",
            "Tenant B rotates hardware keys every 90 days and blocks stale authenticators.",
            "internal"
        ));
        putSeed(seeded, new KnowledgeDocument(
            "doc-platform",
            "platform",
            "Platform retention",
            "Platform operators can inspect tenant deletion evidence but cannot leak cross-tenant content.",
            "restricted"
        ));
        return seeded;
    }

    private void putSeed(Map<String, KnowledgeDocument> target, KnowledgeDocument document) {
        KnowledgeDocument normalized = normalize(document);
        target.put(normalized.id(), normalized);
    }

    private List<String> tenantIds(Map<String, KnowledgeDocument> documents) {
        return documents.values().stream()
            .map(KnowledgeDocument::tenantId)
            .distinct()
            .sorted()
            .toList();
    }

    private DemoSessionSummary sessionSummary(String sessionId) {
        String key = sessionKey(sessionId);
        return new DemoSessionSummary(
            key,
            !CANONICAL_SESSION_ID.equals(key),
            DEMO_SESSION_TTL.toHours()
        );
    }

    private String sessionKey(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return CANONICAL_SESSION_ID;
        }
        String normalized = sessionId.trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120);
        }
        return normalized.replaceAll("[^A-Za-z0-9_.:-]", "-");
    }

    private void touch(String key) {
        sessionTouchedAt.put(key, Instant.now());
    }

    private void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minus(DEMO_SESSION_TTL);
        sessionTouchedAt.forEach((key, touchedAt) -> {
            if (!CANONICAL_SESSION_ID.equals(key) && touchedAt.isBefore(cutoff)) {
                sessionTouchedAt.remove(key);
                sessionDocuments.remove(key);
            }
        });
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

    public record ProofCheck(String id, String label, boolean passed, String evidence) {}

    public record BoundaryProof(boolean passed, String summary, List<ProofCheck> checks) {}

    public record DemoSessionSummary(String sessionId, boolean isolated, long ttlHours) {}

    public record TenantGuardDashboard(
        List<TenantGuardScenario> scenarios,
        DocumentStats stats,
        SearchComparison defaultComparison,
        CatalogSummary tenantUserCatalog,
        CatalogSummary platformAdminCatalog,
        ActionDecision crossTenantDenied,
        ActionDecision writeActionPreview,
        TenantDeletionPreview deletionPreview,
        BoundaryProof boundaryProof,
        DemoSessionSummary session
    ) {}

    public record ActionDecision(
        boolean success,
        boolean confirmationRequired,
        String message,
        String errorCode,
        Map<String, Object> data
    ) {
        static ActionDecision failure(String errorCode, String message) {
            return failure(errorCode, message, Map.of());
        }

        static ActionDecision failure(String errorCode, String message, Map<String, Object> data) {
            return new ActionDecision(false, false, message, errorCode, data);
        }
    }

    public record TenantDeletionResult(
        boolean success,
        String errorCode,
        int deletedDocuments,
        List<String> deletedIds,
        String message,
        String policyDecision,
        List<String> remainingTenantIds
    ) {}
}
