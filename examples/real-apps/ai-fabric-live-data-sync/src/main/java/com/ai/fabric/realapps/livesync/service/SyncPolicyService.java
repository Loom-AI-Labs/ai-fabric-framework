package com.ai.fabric.realapps.livesync.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.domain.SyncPolicy;
import com.ai.fabric.realapps.livesync.repository.SyncPolicyRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncPolicyService {

    private final SyncPolicyRepository repository;

    @Transactional
    @AIProcess(
        entityType = SyncPolicy.ENTITY_TYPE,
        processType = "create",
        generateEmbedding = true,
        indexForSearch = true,
        indexingStrategy = IndexingStrategy.SYNC
    )
    public SyncPolicy createPolicy(
        String workspaceId,
        String recordKey,
        String title,
        String guidance,
        String audience,
        String status,
        LocalDate effectiveDate
    ) {
        SyncPolicy policy = new SyncPolicy();
        policy.setWorkspaceId(SyncEntitySupport.requireText(workspaceId, "workspaceId"));
        policy.setRecordKey(SyncEntitySupport.requireRecordKey(recordKey));
        policy.setId(SyncEntitySupport.entityId(workspaceId, recordKey));
        apply(policy, title, guidance, audience, status, effectiveDate);
        policy.setRevision(1);
        policy.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(policy);
    }

    @Transactional
    @AIProcess(
        entityType = SyncPolicy.ENTITY_TYPE,
        processType = "update",
        generateEmbedding = true,
        indexForSearch = true,
        indexingStrategy = IndexingStrategy.AUTO
    )
    public SyncPolicy updatePolicy(
        String workspaceId,
        String recordKey,
        String title,
        String guidance,
        String audience,
        String status,
        LocalDate effectiveDate
    ) {
        SyncPolicy policy = require(workspaceId, recordKey);
        apply(policy, title, guidance, audience, status, effectiveDate);
        policy.setRevision(policy.getRevision() + 1);
        policy.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(policy);
    }

    @Transactional
    @AIProcess(
        entityType = SyncPolicy.ENTITY_TYPE,
        processType = "delete",
        generateEmbedding = false,
        indexForSearch = false,
        indexingStrategy = IndexingStrategy.SYNC
    )
    public SyncPolicy deletePolicy(String workspaceId, String recordKey) {
        SyncPolicy policy = require(workspaceId, recordKey);
        repository.delete(policy);
        repository.flush();
        return policy;
    }

    public List<SyncPolicy> findAll(String workspaceId) {
        return repository.findAllByWorkspaceIdOrderByRecordKey(workspaceId);
    }

    public SyncPolicy require(String workspaceId, String recordKey) {
        return repository.findByWorkspaceIdAndRecordKey(workspaceId, recordKey)
            .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + recordKey));
    }

    private void apply(
        SyncPolicy policy,
        String title,
        String guidance,
        String audience,
        String status,
        LocalDate effectiveDate
    ) {
        policy.setTitle(SyncEntitySupport.requireText(title, "title"));
        policy.setGuidance(SyncEntitySupport.requireText(guidance, "guidance"));
        policy.setAudience(SyncEntitySupport.requireText(audience, "audience"));
        policy.setStatus(SyncEntitySupport.requireText(status, "status").toUpperCase());
        policy.setEffectiveDate(effectiveDate != null ? effectiveDate : LocalDate.now(ZoneOffset.UTC));
    }
}
