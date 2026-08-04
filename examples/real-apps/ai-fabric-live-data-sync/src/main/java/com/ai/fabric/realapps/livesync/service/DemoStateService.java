package com.ai.fabric.realapps.livesync.service;

import ai.fabric.dto.VectorRecord;
import ai.fabric.service.VectorManagementService;
import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import com.ai.fabric.realapps.livesync.web.DemoModels.AnnotationCoverage;
import com.ai.fabric.realapps.livesync.web.DemoModels.AnnotationUse;
import com.ai.fabric.realapps.livesync.web.DemoModels.DemoState;
import com.ai.fabric.realapps.livesync.web.DemoModels.EntityRecord;
import com.ai.fabric.realapps.livesync.web.DemoModels.VectorProof;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoStateService {

    private final SyncProductService productService;
    private final SyncPolicyService policyService;
    private final SyncGuideService guideService;
    private final VectorManagementService vectorManagementService;
    private final SyncAuditService auditService;
    private final IndexingWorkProjectionService indexingWorkProjectionService;

    public DemoState state(String workspaceId) {
        List<EntityRecord> entities = new ArrayList<>();
        productService.findAll(workspaceId).forEach(entity -> entities.add(productRecord(entity)));
        policyService.findAll(workspaceId).forEach(entity -> entities.add(policyRecord(entity)));
        guideService.findAll(workspaceId).forEach(entity -> entities.add(guideRecord(entity)));

        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        Map<String, Integer> vectorCounts = new LinkedHashMap<>();
        for (EntityKind kind : EntityKind.values()) {
            sourceCounts.put(kind.entityType(), (int) entities.stream()
                .filter(entity -> kind.name().equals(entity.kind()))
                .count());
            vectorCounts.put(kind.entityType(), vectorsForWorkspace(kind, workspaceId).size());
        }

        int vectorTotal = vectorCounts.values().stream().mapToInt(Integer::intValue).sum();
        int synchronizedTotal = (int) entities.stream().filter(entity -> entity.vector().inSync()).count();
        List<com.ai.fabric.realapps.livesync.web.DemoModels.SyncEvent> events =
            auditService.events(workspaceId);
        return new DemoState(
            workspaceId,
            Map.copyOf(sourceCounts),
            Map.copyOf(vectorCounts),
            entities.size(),
            vectorTotal,
            synchronizedTotal,
            List.copyOf(entities),
            events,
            indexingWorkProjectionService.views(workspaceId, events),
            annotationCoverage(),
            Instant.now()
        );
    }

    public VectorProof proof(EntityKind kind, String entityId, Integer sourceRevision) {
        Optional<VectorRecord> vector = vectorManagementService.getVector(kind.entityType(), entityId);
        if (vector.isEmpty()) {
            return VectorProof.missing("No vector exists for this database entity");
        }
        VectorRecord record = vector.get();
        Integer vectorRevision = integerValue(record.getMetadata().get("version"));
        boolean inSync = sourceRevision != null && Objects.equals(sourceRevision, vectorRevision);
        String message = inSync
            ? "Database revision and vector revision match"
            : "Vector revision does not match the database revision";
        return new VectorProof(
            true,
            inSync,
            record.getVectorId(),
            record.getContent(),
            safeMetadata(record.getMetadata()),
            message
        );
    }

    public boolean vectorPresent(EntityKind kind, String entityId) {
        return vectorManagementService.getVector(kind.entityType(), entityId).isPresent();
    }

    private EntityRecord productRecord(SyncProduct product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("summary", product.getSummary());
        fields.put("specification", product.getSpecification());
        fields.put("category", product.getCategory());
        fields.put("price", product.getPrice());
        fields.put("status", product.getStatus());
        return entityRecord(
            EntityKind.PRODUCT,
            product.getId(),
            product.getRecordKey(),
            product.getTitle(),
            product.getRevision(),
            product.getUpdatedAt(),
            fields
        );
    }

    private EntityRecord policyRecord(SyncPolicy policy) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("guidance", policy.getGuidance());
        fields.put("audience", policy.getAudience());
        fields.put("status", policy.getStatus());
        fields.put("effectiveDate", policy.getEffectiveDate());
        return entityRecord(
            EntityKind.POLICY,
            policy.getId(),
            policy.getRecordKey(),
            policy.getTitle(),
            policy.getRevision(),
            policy.getUpdatedAt(),
            fields
        );
    }

    private EntityRecord guideRecord(SyncGuide guide) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("symptoms", guide.getSymptoms());
        fields.put("resolution", guide.getResolution());
        fields.put("productArea", guide.getProductArea());
        fields.put("severity", guide.getSeverity());
        return entityRecord(
            EntityKind.GUIDE,
            guide.getId(),
            guide.getRecordKey(),
            guide.getTitle(),
            guide.getRevision(),
            guide.getUpdatedAt(),
            fields
        );
    }

    private EntityRecord entityRecord(
        EntityKind kind,
        String entityId,
        String recordKey,
        String title,
        Integer revision,
        LocalDateTime updatedAt,
        Map<String, Object> fields
    ) {
        return new EntityRecord(
            kind.name(),
            kind.entityType(),
            recordKey,
            title,
            revision,
            updatedAt.toInstant(ZoneOffset.UTC),
            Map.copyOf(fields),
            proof(kind, entityId, revision)
        );
    }

    private List<VectorRecord> vectorsForWorkspace(EntityKind kind, String workspaceId) {
        return vectorManagementService.getVectorsByEntityType(kind.entityType()).stream()
            .filter(vector -> Objects.equals(workspaceId, stringValue(vector.getMetadata().get("workspaceId"))))
            .toList();
    }

    private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (value != null && !key.toLowerCase().contains("embedding")) {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private AnnotationCoverage annotationCoverage() {
        return new AnnotationCoverage(
            List.of(
                new AnnotationUse(
                    "@EnableAIInfrastructure",
                    "LiveDataSyncApplication",
                    "Marks the Spring Boot application as AI Fabric enabled."
                ),
                new AnnotationUse(
                    "@AICapable",
                    "SyncProduct, SyncPolicy, SyncGuide",
                    "Declares each stable vector entity type, lifecycle strategy, and migration repository."
                ),
                new AnnotationUse(
                    "@AISearchable",
                    "User-facing title and knowledge fields",
                    "Selects and preprocesses the text embedded into each vector."
                ),
                new AnnotationUse(
                    "@AIContext",
                    "Workspace, key, status, source version, and domain metadata",
                    "Stores structured metadata beside vector content, enables workspace filtering, and protects against stale work."
                ),
                new AnnotationUse(
                    "@AIProcess",
                    "Create, update, and delete service methods",
                    "Routes committed entity results into synchronous index, update, or delete processing."
                )
            ),
            "AIEntityDescriptorRegistry and AIEntityProjectionService",
            "AIProcessAspect and AIEntityIndexingGateway",
            "Transaction-aware create/update/delete with durable revision proof"
        );
    }
}
