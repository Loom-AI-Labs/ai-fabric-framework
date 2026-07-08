package com.ai.fabric.realapps.tenantportal.service;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.llm.structured.StructuredJsonExtraction;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import ai.fabric.rag.VectorDatabaseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TenantKnowledgeService {

    private static final String CANONICAL_SESSION_ID = "canonical";
    private static final String VECTOR_ENTITY_TYPE = "tenant-document";
    private static final Duration DEMO_SESSION_TTL = Duration.ofHours(6);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final StructuredJsonExtractor STRUCTURED_JSON_EXTRACTOR = new StructuredJsonExtractor();
    private static final Set<String> SUPPORTED_ACTIONS = Set.of("archive_document");

    private final Map<String, Map<String, KnowledgeDocument>> sessionDocuments = new ConcurrentHashMap<>();
    private final Map<String, Instant> sessionTouchedAt = new ConcurrentHashMap<>();
    private final Map<String, VectorIndexState> sessionIndexState = new ConcurrentHashMap<>();
    private final ObjectProvider<AICoreService> aiCoreServiceProvider;
    private final ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider;

    public TenantKnowledgeService() {
        this(null, null);
    }

    @Autowired
    public TenantKnowledgeService(
        ObjectProvider<AICoreService> aiCoreServiceProvider,
        ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider
    ) {
        this.aiCoreServiceProvider = aiCoreServiceProvider;
        this.vectorDatabaseServiceProvider = vectorDatabaseServiceProvider;
        resetDemoData();
    }

    public synchronized TenantGuardDashboard resetDemoData() {
        return resetDemoData(null);
    }

    public synchronized TenantGuardDashboard resetDemoData(String sessionId) {
        cleanupExpiredSessions();
        String key = sessionKey(sessionId);
        Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>(seededDocuments());
        sessionDocuments.put(key, documents);
        touch(key);
        refreshAiIndex(key, documents);
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
            sessionSummary(sessionId),
            indexProof(sessionId)
        );
    }

    public TenantRagResponse queryTenantKnowledge(String sessionId, TenantQueryRequest request) {
        TenantQueryRequest effective = request != null
            ? request
            : new TenantQueryRequest("tenant-a", "USER", "How do I configure VPN?", 5);
        UserContext user = requireUser(new UserContext(effective.tenantId(), effective.role()));
        String query = requireText(effective.query(), "query");
        int limit = effective.limit() > 0 ? Math.min(effective.limit(), 10) : 5;
        String key = sessionKey(sessionId);
        Map<String, KnowledgeDocument> documents = documentsForSession(sessionId);
        ensureAiIndexCurrent(key, documents);

        AICoreService aiCoreService = aiCoreService();
        if (aiCoreService == null) {
            return TenantRagResponse.failure(
                query,
                user,
                metadataFilter(key, user),
                indexProof(sessionId),
                "AI_CORE_UNAVAILABLE",
                "AICoreService is not available, so the demo cannot run AI Fabric retrieval."
            );
        }

        Map<String, Object> filter = metadataFilter(key, user);
        AISearchRequest searchRequest = AISearchRequest.builder()
            .query(query)
            .entityType(VECTOR_ENTITY_TYPE)
            .limit(limit)
            .threshold(0.0d)
            .metadata(filter)
            .build();

        try {
            AISearchResponse response = aiCoreService.performSearch(searchRequest);
            List<TenantRagHit> hits = toTenantHits(key, user, response, documents, limit);
            BoundaryProof proof = retrievalBoundaryProof(user, filter, hits);
            VectorIndexProof indexProof = indexProof(sessionId);
            GeneratedTenantAnswer generatedAnswer = generateTenantAnswer(aiCoreService, user, query, hits);
            return new TenantRagResponse(
                true,
                null,
                query,
                user,
                generatedAnswer.content(),
                filter,
                hits,
                citationsFor(hits),
                proof,
                indexProof,
                generatedAnswer.requestId() != null ? generatedAnswer.requestId() : response != null ? response.getRequestId() : null,
                generatedAnswer.processingTimeMs() != null ? generatedAnswer.processingTimeMs() : response != null ? response.getProcessingTimeMs() : null,
                generatedAnswer.model()
            );
        } catch (Exception ex) {
            return TenantRagResponse.failure(
                query,
                user,
                filter,
                indexProof(sessionId),
                ex.getClass().getSimpleName(),
                ex.getMessage()
            );
        }
    }

    public ActionDecision executeNaturalLanguageAction(String sessionId, TenantNlActionRequest request) {
        TenantNlActionRequest effective = request != null
            ? request
            : new TenantNlActionRequest("tenant-a", "USER", "Archive our VPN setup document.", false);
        UserContext user = requireUser(new UserContext(effective.tenantId(), effective.role()));
        String instruction = requireText(effective.instruction(), "instruction");
        String key = sessionKey(sessionId);
        Map<String, KnowledgeDocument> documents = documentsForSession(sessionId);
        AICoreService aiCoreService = aiCoreService();
        if (aiCoreService == null) {
            return ActionDecision.failure(
                "AI_CORE_UNAVAILABLE",
                "AICoreService is not available, so the demo cannot resolve natural-language actions.",
                Map.of("policyDecision", "DENIED", "instruction", instruction)
            );
        }

        try {
            AIGenerationResponse response = aiCoreService.generateContent(
                AIGenerationRequest.builder()
                    .entityId("tenant-action-" + UUID.randomUUID())
                    .entityType("tenant-guard-action")
                    .generationType("natural-language-action-resolution")
                    .systemPrompt(naturalLanguageActionSystemPrompt())
                    .prompt(naturalLanguageActionPrompt(key, user, instruction, documents))
                    .maxTokens(350)
                    .temperature(0.0d)
                    .parameters(StructuredJsonProviderHints.jsonObjectResponseParameters())
                    .build(),
                LlmPurpose.ORCHESTRATION
            );
            if (response == null || !StringUtils.hasText(response.getContent())) {
                return ActionDecision.failure(
                    "NL_ACTION_EMPTY_RESPONSE",
                    "The LLM returned no action resolution JSON.",
                    Map.of("policyDecision", "DENIED", "instruction", instruction)
                );
            }
            NaturalLanguageActionDraft draft = parseNaturalLanguageActionDraft(response.getContent());
            if (!StringUtils.hasText(draft.actionId()) || !StringUtils.hasText(draft.documentId())) {
                return ActionDecision.failure(
                    "TARGET_REQUIRED",
                    "The LLM could not resolve a concrete action target from the natural-language request.",
                    naturalLanguageActionData("DENIED", user, instruction, draft, response, Map.of())
                );
            }
            ActionDecision decision = executeAction(
                sessionId,
                user,
                new TenantActionRequest(
                    draft.actionId(),
                    draft.documentId(),
                    draft.accessMode() != null ? draft.accessMode() : ActionAccessMode.WRITE_ONLY,
                    effective.confirmed()
                )
            );
            return new ActionDecision(
                decision.success(),
                decision.confirmationRequired(),
                decision.message(),
                decision.errorCode(),
                mergeData(
                    decision.data(),
                    naturalLanguageActionData(
                        String.valueOf(decision.data().getOrDefault("policyDecision", decision.success() ? "APPROVED" : "DENIED")),
                        user,
                        instruction,
                        draft,
                        response,
                        Map.of("confirmed", effective.confirmed())
                    )
                )
            );
        } catch (Exception ex) {
            return ActionDecision.failure(
                "NL_ACTION_PARSE_FAILED",
                "The LLM action resolution failed closed: " + ex.getMessage(),
                Map.of(
                    "policyDecision", "DENIED",
                    "instruction", instruction,
                    "subjectTenantId", user.tenantId(),
                    "subjectRole", user.role()
                )
            );
        }
    }

    public VectorIndexProof seedAiIndex(String sessionId) {
        String key = sessionKey(sessionId);
        refreshAiIndex(key, documentsForSession(sessionId));
        return indexProof(sessionId);
    }

    public VectorIndexProof indexProof(String sessionId) {
        String key = sessionKey(sessionId);
        VectorDatabaseService vectorDatabaseService = vectorDatabaseService();
        if (vectorDatabaseService == null) {
            VectorIndexState state = sessionIndexState.get(key);
            String message = state != null && StringUtils.hasText(state.message())
                ? state.message()
                : "VectorDatabaseService is not available.";
            return VectorIndexProof.unavailable(message);
        }

        try {
            Map<String, Object> diagnostics = vectorDatabaseService.adminDiagnostics();
            VectorScanPage page = vectorDatabaseService.scan(VectorScanRequest.builder()
                .entityType(VECTOR_ENTITY_TYPE)
                .metadataEquals(Map.of("sessionId", key))
                .limit(200)
                .includeContent(false)
                .includeEmbedding(false)
                .includeMetadata(true)
                .build());
            List<VectorRecord> vectors = page != null && page.getVectors() != null ? page.getVectors() : List.of();
            Map<String, Long> byTenant = vectors.stream()
                .map(VectorRecord::getMetadata)
                .filter(Objects::nonNull)
                .map(metadata -> String.valueOf(metadata.getOrDefault("tenantId", "unknown")))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
            List<ProofCheck> checks = List.of(
                new ProofCheck(
                    "vector-service-available",
                    "AI Fabric VectorDatabaseService is available",
                    true,
                    vectorDatabaseService.vectorProviderName()
                ),
                new ProofCheck(
                    "metadata-filtered-search-supported",
                    "Provider supports metadata-filtered vector search",
                    vectorDatabaseService.supportsSearchMetadataFiltering(),
                    vectorDatabaseService.vectorSearchFilterMode()
                ),
                new ProofCheck(
                    "metadata-filtered-scan-supported",
                    "Provider supports metadata-filtered vector scan proof",
                    vectorDatabaseService.supportsScanMetadataFiltering(),
                    vectorDatabaseService.vectorScanFilterMode()
                ),
                new ProofCheck(
                    "session-index-is-scoped",
                    "Index proof scans only the current demo session",
                    vectors.stream().allMatch(vector -> vector.getMetadata() != null
                        && key.equals(String.valueOf(vector.getMetadata().get("sessionId")))),
                    String.valueOf(vectors.size()) + " indexed vectors"
                )
            );
            return new VectorIndexProof(
                true,
                null,
                "READY",
                vectorDatabaseService.vectorProviderName(),
                vectorDatabaseService.vectorSearchFilterMode(),
                vectorDatabaseService.vectorScanFilterMode(),
                vectorDatabaseService.supportsSearchMetadataFiltering(),
                vectorDatabaseService.supportsScanMetadataFiltering(),
                vectors.size(),
                byTenant,
                checks,
                diagnosticsSummary(diagnostics),
                stateMessage(key)
            );
        } catch (Exception ex) {
            return new VectorIndexProof(
                false,
                ex.getClass().getSimpleName(),
                "NOT_READY",
                vectorDatabaseService.vectorProviderName(),
                vectorDatabaseService.vectorSearchFilterMode(),
                vectorDatabaseService.vectorScanFilterMode(),
                vectorDatabaseService.supportsSearchMetadataFiltering(),
                vectorDatabaseService.supportsScanMetadataFiltering(),
                0,
                Map.of(),
                List.of(new ProofCheck("index-proof-failed", "Index proof failed closed", false, ex.getMessage())),
                diagnosticsSummary(vectorDatabaseService.adminDiagnostics()),
                ex.getMessage()
            );
        }
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
        if (!SUPPORTED_ACTIONS.contains(actionId)) {
            return ActionDecision.failure(
                "ACTION_NOT_ALLOWED",
                "Action " + actionId + " is not registered for this demo.",
                policyData("DENIED", "Only registered Tenant Guard actions can execute.", user, null, actionId, effective.accessMode())
            );
        }
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
                tenantIds(documentsForSession(sessionId)),
                0,
                List.of(),
                indexProof(sessionId)
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
                tenantIds(documentsForSession(sessionId)),
                0,
                List.of(),
                indexProof(sessionId)
            );
        }
        Map<String, KnowledgeDocument> documents = documentsForSession(sessionId);
        List<String> deletedIds = documents.values().stream()
            .filter(document -> tenant.equals(document.tenantId()))
            .map(KnowledgeDocument::id)
            .sorted()
            .toList();
        List<String> deletedVectorEntityIds = deleteIndexedDocuments(sessionKey(sessionId), deletedIds);
        deletedIds.forEach(documents::remove);
        return new TenantDeletionResult(
            true,
            null,
            deletedIds.size(),
            deletedIds,
            "Deleted only " + tenant + " evidence. Other tenant documents remain isolated.",
            "APPROVED",
            tenantIds(documents),
            deletedVectorEntityIds.size(),
            deletedVectorEntityIds,
            indexProof(sessionId)
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
        metadata.put("visibleToUser", !"restricted".equals(document.visibility()));
        metadata.put("documentId", document.id());
        metadata.put("title", document.title());
        metadata.put("entityType", VECTOR_ENTITY_TYPE);
        return Map.copyOf(metadata);
    }

    private void ensureAiIndexCurrent(String key, Map<String, KnowledgeDocument> documents) {
        VectorIndexState state = sessionIndexState.get(key);
        if (state == null || state.indexedDocuments() < documents.size()) {
            refreshAiIndex(key, documents);
        }
    }

    private synchronized void refreshAiIndex(String key, Map<String, KnowledgeDocument> documents) {
        AICoreService aiCoreService = aiCoreService();
        VectorDatabaseService vectorDatabaseService = vectorDatabaseService();
        if (aiCoreService == null || vectorDatabaseService == null) {
            sessionIndexState.put(key, new VectorIndexState(
                false,
                0,
                "AICoreService or VectorDatabaseService is not available.",
                Instant.now()
            ));
            return;
        }
        try {
            deleteIndexedSession(key, documents.values().stream().map(KnowledgeDocument::id).toList());
            int indexed = 0;
            for (KnowledgeDocument document : documents.values().stream().sorted(Comparator.comparing(KnowledgeDocument::id)).toList()) {
                String entityId = vectorEntityId(key, document.id());
                AIEmbeddingResponse embedding = aiCoreService.generateEmbedding(AIEmbeddingRequest.builder()
                    .text(indexContent(document))
                    .entityType(VECTOR_ENTITY_TYPE)
                    .entityId(entityId)
                    .build());
                if (embedding == null || embedding.getEmbedding() == null || embedding.getEmbedding().isEmpty()) {
                    throw new IllegalStateException("Embedding provider returned no vector for " + document.id());
                }
                vectorDatabaseService.storeVector(
                    VECTOR_ENTITY_TYPE,
                    entityId,
                    indexContent(document),
                    embedding.getEmbedding(),
                    indexedMetadata(key, document)
                );
                indexed++;
            }
            sessionIndexState.put(key, new VectorIndexState(true, indexed, "AI Fabric index refreshed.", Instant.now()));
        } catch (Exception ex) {
            sessionIndexState.put(key, new VectorIndexState(false, 0, ex.getMessage(), Instant.now()));
        }
    }

    private List<String> deleteIndexedSession(String key, List<String> documentIds) {
        VectorDatabaseService vectorDatabaseService = vectorDatabaseService();
        if (vectorDatabaseService == null) {
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        try {
            VectorScanPage page = vectorDatabaseService.scan(VectorScanRequest.builder()
                .entityType(VECTOR_ENTITY_TYPE)
                .metadataEquals(Map.of("sessionId", key))
                .limit(200)
                .includeContent(false)
                .includeEmbedding(false)
                .includeMetadata(true)
                .build());
            if (page != null && page.getVectors() != null) {
                for (VectorRecord vector : page.getVectors()) {
                    if (StringUtils.hasText(vector.getEntityId())
                        && vectorDatabaseService.removeVector(VECTOR_ENTITY_TYPE, vector.getEntityId())) {
                        removed.add(vector.getEntityId());
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to known seeded ids below.
        }
        for (String documentId : documentIds) {
            String entityId = vectorEntityId(key, documentId);
            if (!removed.contains(entityId) && removeVectorIfExists(vectorDatabaseService, entityId)) {
                removed.add(entityId);
            }
        }
        return removed;
    }

    private List<String> deleteIndexedDocuments(String key, List<String> documentIds) {
        VectorDatabaseService vectorDatabaseService = vectorDatabaseService();
        if (vectorDatabaseService == null || documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        for (String documentId : documentIds) {
            String entityId = vectorEntityId(key, documentId);
            if (removeVectorIfExists(vectorDatabaseService, entityId)) {
                removed.add(entityId);
            }
        }
        return removed;
    }

    private boolean removeVectorIfExists(VectorDatabaseService vectorDatabaseService, String entityId) {
        try {
            if (!vectorDatabaseService.vectorExists(VECTOR_ENTITY_TYPE, entityId)) {
                return false;
            }
            return vectorDatabaseService.removeVector(VECTOR_ENTITY_TYPE, entityId);
        } catch (Exception ex) {
            return vectorDatabaseService.removeVector(VECTOR_ENTITY_TYPE, entityId);
        }
    }

    private Map<String, Object> indexedMetadata(String key, KnowledgeDocument document) {
        Map<String, Object> metadata = new LinkedHashMap<>(metadata(document));
        metadata.put("sessionId", key);
        metadata.put("visibleToUser", !"restricted".equals(document.visibility()));
        return Map.copyOf(metadata);
    }

    private Map<String, Object> metadataFilter(String key, UserContext user) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", key);
        if (!isPlatformAdmin(user)) {
            metadata.put("tenantId", user.tenantId());
            metadata.put("visibleToUser", true);
        }
        return Map.copyOf(metadata);
    }

    private List<TenantRagHit> toTenantHits(
        String key,
        UserContext user,
        AISearchResponse response,
        Map<String, KnowledgeDocument> documents,
        int limit
    ) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }
        Map<String, TenantRagHit> uniqueHits = new LinkedHashMap<>();
        for (Map<String, Object> row : response.getResults()) {
            Optional<TenantRagHit> hit = toTenantHit(key, user, row, documents);
            hit.ifPresent(value -> uniqueHits.putIfAbsent(value.id(), value));
            if (uniqueHits.size() >= limit) {
                break;
            }
        }
        return List.copyOf(uniqueHits.values());
    }

    private Optional<TenantRagHit> toTenantHit(
        String key,
        UserContext user,
        Map<String, Object> row,
        Map<String, KnowledgeDocument> documents
    ) {
        if (row == null) {
            return Optional.empty();
        }
        Map<String, Object> metadata = parseMetadata(row.get("metadata"));
        String documentId = String.valueOf(metadata.getOrDefault("documentId", ""));
        if (!StringUtils.hasText(documentId)) {
            documentId = stripVectorEntityPrefix(key, String.valueOf(row.getOrDefault("id", "")));
        }
        KnowledgeDocument document = documents.get(documentId);
        if (document == null) {
            return Optional.empty();
        }
        if (!key.equals(String.valueOf(metadata.get("sessionId")))) {
            return Optional.empty();
        }
        if (!document.tenantId().equals(String.valueOf(metadata.get("tenantId")))) {
            return Optional.empty();
        }
        if (!canRead(user, document)) {
            return Optional.empty();
        }
        return Optional.of(new TenantRagHit(
            document.id(),
            document.title(),
            document.tenantId(),
            document.visibility(),
            document.content(),
            number(row.get("score")),
            metadata
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(Object metadata) {
        if (metadata instanceof Map<?, ?> raw) {
            Map<String, Object> parsed = new LinkedHashMap<>();
            raw.forEach((key, value) -> parsed.put(String.valueOf(key), value));
            return parsed;
        }
        if (metadata instanceof String text && StringUtils.hasText(text)) {
            try {
                return OBJECT_MAPPER.readValue(text, MAP_TYPE);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private BoundaryProof retrievalBoundaryProof(UserContext user, Map<String, Object> filter, List<TenantRagHit> hits) {
        List<ProofCheck> checks = List.of(
            new ProofCheck(
                "request-has-session-filter",
                "AI Fabric search request includes current session filter",
                filter.containsKey("sessionId"),
                filter.toString()
            ),
            new ProofCheck(
                "request-has-tenant-filter",
                "Tenant users search only their own tenant evidence",
                isPlatformAdmin(user) || user.tenantId().equals(filter.get("tenantId")),
                filter.toString()
            ),
            new ProofCheck(
                "restricted-evidence-hidden",
                "Restricted evidence follows role policy",
                isPlatformAdmin(user) || hits.stream().noneMatch(hit -> "restricted".equals(hit.visibility())),
                hits.stream().map(TenantRagHit::id).toList().toString()
            ),
            new ProofCheck(
                "results-pass-app-boundary-check",
                "Returned evidence passes app-side tenant and visibility verification",
                hits.stream().allMatch(hit -> isPlatformAdmin(user) || user.tenantId().equals(hit.tenantId())),
                hits.stream().map(hit -> hit.id() + ":" + hit.tenantId()).toList().toString()
            )
        );
        boolean passed = checks.stream().allMatch(ProofCheck::passed);
        return new BoundaryProof(
            passed,
            passed
                ? "AI Fabric retrieval and app-side verification kept evidence inside the caller boundary."
                : "Retrieval proof failed; results should not be trusted.",
            checks
        );
    }

    private String evidenceSummary(UserContext user, String query, List<TenantRagHit> hits) {
        if (hits.isEmpty()) {
            return "AI Fabric retrieved no allowed evidence for " + user.tenantId()
                + " using query \"" + query + "\".";
        }
        String scope = isPlatformAdmin(user) ? "platform admin" : user.tenantId();
        String evidence = hits.stream()
            .limit(3)
            .map(hit -> hit.title() + ": " + hit.content())
            .collect(Collectors.joining(" "));
        return "Based only on AI Fabric evidence allowed for " + scope + ", " + evidence;
    }

    private GeneratedTenantAnswer generateTenantAnswer(
        AICoreService aiCoreService,
        UserContext user,
        String query,
        List<TenantRagHit> hits
    ) {
        if (hits.isEmpty()) {
            return new GeneratedTenantAnswer(
                evidenceSummary(user, query, hits),
                null,
                null,
                null
            );
        }
        AIGenerationResponse response = aiCoreService.generateContent(
            AIGenerationRequest.builder()
                .entityId("tenant-answer-" + UUID.randomUUID())
                .entityType("tenant-guard-answer")
                .generationType("tenant-rag-answer")
                .systemPrompt(tenantAnswerSystemPrompt())
                .prompt(tenantAnswerPrompt(user, query, hits))
                .maxTokens(550)
                .temperature(0.0d)
                .build(),
            LlmPurpose.GENERATION
        );
        if (response == null || !StringUtils.hasText(response.getContent())) {
            throw new IllegalStateException("LLM returned no answer for tenant-safe evidence.");
        }
        return new GeneratedTenantAnswer(
            response.getContent().trim(),
            response.getRequestId(),
            response.getProcessingTimeMs(),
            response.getModel()
        );
    }

    private String tenantAnswerSystemPrompt() {
        return """
            You are AI Fabric Tenant Guard's answer generator.
            Answer only from the evidence provided by the backend.
            The backend already filtered evidence by trusted tenant, session, and visibility metadata.
            Never use outside knowledge. Never mention documents that are not in the evidence list.
            Cite evidence with the exact document id in square brackets, for example [doc-a].
            If the evidence is insufficient, say that the allowed evidence does not answer the question.
            """;
    }

    private String tenantAnswerPrompt(UserContext user, String query, List<TenantRagHit> hits) {
        String evidence = hits.stream()
            .map(hit -> "- id: " + hit.id()
                + "\n  title: " + hit.title()
                + "\n  tenantId: " + hit.tenantId()
                + "\n  visibility: " + hit.visibility()
                + "\n  content: " + hit.content())
            .collect(Collectors.joining("\n"));
        return """
            Caller:
            tenantId: %s
            role: %s

            User question:
            %s

            Allowed evidence:
            %s

            Write a concise answer using only the allowed evidence. Include citations.
            """.formatted(user.tenantId(), user.role(), query, evidence);
    }

    private List<TenantRagCitation> citationsFor(List<TenantRagHit> hits) {
        return hits.stream()
            .map(hit -> new TenantRagCitation(hit.id(), hit.title(), hit.tenantId(), hit.score()))
            .toList();
    }

    private String naturalLanguageActionSystemPrompt() {
        return """
            You resolve a user's natural-language Tenant Guard action into a JSON action draft.
            Return only one JSON object. Do not include prose or markdown.
            Allowed action ids: archive_document.
            Allowed accessMode values: READ, WRITE_ONLY, READ_WRITE.
            Select documentId only from the provided actionTargetCatalog.
            If the user has not identified a concrete target, return null for actionId and documentId.
            Do not decide authorization. The backend policy engine will enforce tenant, role, and confirmation.
            Required JSON shape:
            {
              "actionId": "archive_document|null",
              "documentId": "document id or null",
              "accessMode": "WRITE_ONLY",
              "reason": "short reason",
              "confidence": 0.0
            }
            """;
    }

    private String naturalLanguageActionPrompt(
        String sessionKey,
        UserContext user,
        String instruction,
        Map<String, KnowledgeDocument> documents
    ) {
        String catalog = documents.values().stream()
            .sorted(Comparator.comparing(KnowledgeDocument::id))
            .map(document -> Map.<String, Object>of(
                "id", document.id(),
                "tenantId", document.tenantId(),
                "title", document.title(),
                "visibility", document.visibility()
            ))
            .map(this::writeJson)
            .collect(Collectors.joining("\n"));
        return """
            Trusted caller context:
            sessionId: %s
            tenantId: %s
            role: %s

            User instruction:
            %s

            actionTargetCatalog:
            %s

            Resolve the action draft now.
            """.formatted(sessionKey, user.tenantId(), user.role(), instruction, catalog);
    }

    private NaturalLanguageActionDraft parseNaturalLanguageActionDraft(String rawContent) {
        StructuredJsonExtraction extraction = STRUCTURED_JSON_EXTRACTOR.extractFirstJson(rawContent);
        if (extraction == null || !extraction.jsonFound() || extraction.truncationSuspected()) {
            throw new IllegalArgumentException("LLM did not return a complete JSON object.");
        }
        try {
            Map<String, Object> json = OBJECT_MAPPER.readValue(extraction.payload(), MAP_TYPE);
            return new NaturalLanguageActionDraft(
                nullableText(json.get("actionId")),
                nullableText(json.get("documentId")),
                parseAccessMode(nullableText(json.get("accessMode"))),
                nullableText(json.get("reason")),
                number(json.get("confidence"))
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid action JSON: " + ex.getMessage(), ex);
        }
    }

    private ActionAccessMode parseAccessMode(String value) {
        if (!StringUtils.hasText(value)) {
            return ActionAccessMode.WRITE_ONLY;
        }
        try {
            return ActionAccessMode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unsupported accessMode: " + value);
        }
    }

    private Map<String, Object> naturalLanguageActionData(
        String policyDecision,
        UserContext user,
        String instruction,
        NaturalLanguageActionDraft draft,
        AIGenerationResponse response,
        Map<String, Object> extra
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyDecision", policyDecision);
        data.put("instruction", instruction);
        data.put("subjectTenantId", user.tenantId());
        data.put("subjectRole", user.role());
        putIfPresent(data, "llmActionId", draft.actionId());
        putIfPresent(data, "llmDocumentId", draft.documentId());
        putIfPresent(data, "llmAccessMode", draft.accessMode() != null ? draft.accessMode().name() : null);
        putIfPresent(data, "llmReason", draft.reason());
        putIfPresent(data, "llmConfidence", draft.confidence());
        putIfPresent(data, "llmRequestId", response.getRequestId());
        putIfPresent(data, "llmModel", response.getModel());
        data.putAll(extra);
        return Map.copyOf(data);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> mergeData(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (first != null) {
            merged.putAll(first);
        }
        if (second != null) {
            merged.putAll(second);
        }
        return Map.copyOf(merged);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return value.toString();
        }
    }

    private String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private Map<String, Object> diagnosticsSummary(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        List.of(
            "provider",
            "nativeClient",
            "supportsSearchMetadataFiltering",
            "supportsScanMetadataFiltering",
            "searchFilterMode",
            "scanFilterMode",
            "metadataFilterSubset",
            "persistent",
            "productionProfileSafe"
        ).forEach(key -> {
            if (diagnostics.containsKey(key)) {
                summary.put(key, diagnostics.get(key));
            }
        });
        return Map.copyOf(summary);
    }

    private String stateMessage(String key) {
        VectorIndexState state = sessionIndexState.get(key);
        return state != null ? state.message() : "";
    }

    private String indexContent(KnowledgeDocument document) {
        return document.title() + "\n" + document.content();
    }

    private String vectorEntityId(String key, String documentId) {
        return key + ":" + documentId;
    }

    private String stripVectorEntityPrefix(String key, String entityId) {
        String prefix = key + ":";
        return entityId != null && entityId.startsWith(prefix) ? entityId.substring(prefix.length()) : entityId;
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private AICoreService aiCoreService() {
        return aiCoreServiceProvider != null ? aiCoreServiceProvider.getIfAvailable() : null;
    }

    private VectorDatabaseService vectorDatabaseService() {
        return vectorDatabaseServiceProvider != null ? vectorDatabaseServiceProvider.getIfAvailable() : null;
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
                sessionIndexState.remove(key);
                deleteIndexedSession(key, seededDocuments().keySet().stream().toList());
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

    public record TenantQueryRequest(String tenantId, String role, String query, int limit) {}

    public record TenantNlActionRequest(String tenantId, String role, String instruction, boolean confirmed) {}

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

    public record TenantRagHit(
        String id,
        String title,
        String tenantId,
        String visibility,
        String content,
        Double score,
        Map<String, Object> metadata
    ) {}

    public record TenantRagCitation(String id, String title, String tenantId, Double score) {}

    public record VectorIndexProof(
        boolean available,
        String errorCode,
        String status,
        String provider,
        String searchFilterMode,
        String scanFilterMode,
        boolean supportsSearchMetadataFiltering,
        boolean supportsScanMetadataFiltering,
        int indexedDocuments,
        Map<String, Long> indexedByTenant,
        List<ProofCheck> checks,
        Map<String, Object> diagnostics,
        String message
    ) {
        static VectorIndexProof unavailable(String message) {
            return new VectorIndexProof(
                false,
                "VECTOR_SERVICE_UNAVAILABLE",
                "NOT_READY",
                "",
                "",
                "",
                false,
                false,
                0,
                Map.of(),
                List.of(new ProofCheck("vector-service-unavailable", "Vector service is unavailable", false, message)),
                Map.of(),
                message
            );
        }
    }

    public record TenantRagResponse(
        boolean success,
        String errorCode,
        String query,
        UserContext user,
        String answer,
        Map<String, Object> metadataFilter,
        List<TenantRagHit> hits,
        List<TenantRagCitation> citations,
        BoundaryProof boundaryProof,
        VectorIndexProof indexProof,
        String requestId,
        Long processingTimeMs,
        String model
    ) {
        static TenantRagResponse failure(
            String query,
            UserContext user,
            Map<String, Object> metadataFilter,
            VectorIndexProof indexProof,
            String errorCode,
            String message
        ) {
            return new TenantRagResponse(
                false,
                errorCode,
                query,
                user,
                message,
                metadataFilter,
                List.of(),
                List.of(),
                new BoundaryProof(
                    false,
                    "AI Fabric retrieval failed closed.",
                    List.of(new ProofCheck("retrieval-failed", "Retrieval failed closed", false, message))
                ),
                indexProof,
                null,
                null,
                null
            );
        }
    }

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
        DemoSessionSummary session,
        VectorIndexProof indexProof
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
        List<String> remainingTenantIds,
        int deletedVectors,
        List<String> deletedVectorEntityIds,
        VectorIndexProof indexProof
    ) {}

    private record VectorIndexState(boolean ready, int indexedDocuments, String message, Instant refreshedAt) {}

    private record GeneratedTenantAnswer(String content, String requestId, Long processingTimeMs, String model) {}

    private record NaturalLanguageActionDraft(
        String actionId,
        String documentId,
        ActionAccessMode accessMode,
        String reason,
        Double confidence
    ) {}
}
