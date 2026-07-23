package com.ai.fabric.realapps.livesync.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.domain.SyncGuide;
import com.ai.fabric.realapps.livesync.repository.SyncGuideRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncGuideService {

    private final SyncGuideRepository repository;

    @Transactional
    @AIProcess(
        entityType = SyncGuide.ENTITY_TYPE,
        processType = "create",
        generateEmbedding = true,
        indexForSearch = true,
        indexingStrategy = IndexingStrategy.SYNC
    )
    public SyncGuide createGuide(
        String workspaceId,
        String recordKey,
        String title,
        String symptoms,
        String resolution,
        String productArea,
        String severity
    ) {
        SyncGuide guide = new SyncGuide();
        guide.setWorkspaceId(SyncEntitySupport.requireText(workspaceId, "workspaceId"));
        guide.setRecordKey(SyncEntitySupport.requireRecordKey(recordKey));
        guide.setId(SyncEntitySupport.entityId(workspaceId, recordKey));
        apply(guide, title, symptoms, resolution, productArea, severity);
        guide.setRevision(1);
        guide.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(guide);
    }

    @Transactional
    @AIProcess(
        entityType = SyncGuide.ENTITY_TYPE,
        processType = "update",
        generateEmbedding = true,
        indexForSearch = true,
        indexingStrategy = IndexingStrategy.SYNC
    )
    public SyncGuide updateGuide(
        String workspaceId,
        String recordKey,
        String title,
        String symptoms,
        String resolution,
        String productArea,
        String severity
    ) {
        SyncGuide guide = require(workspaceId, recordKey);
        apply(guide, title, symptoms, resolution, productArea, severity);
        guide.setRevision(guide.getRevision() + 1);
        guide.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(guide);
    }

    @Transactional
    @AIProcess(
        entityType = SyncGuide.ENTITY_TYPE,
        processType = "delete",
        generateEmbedding = false,
        indexForSearch = false,
        indexingStrategy = IndexingStrategy.AUTO
    )
    public SyncGuide deleteGuide(String workspaceId, String recordKey) {
        SyncGuide guide = require(workspaceId, recordKey);
        repository.delete(guide);
        repository.flush();
        return guide;
    }

    public List<SyncGuide> findAll(String workspaceId) {
        return repository.findAllByWorkspaceIdOrderByRecordKey(workspaceId);
    }

    public SyncGuide require(String workspaceId, String recordKey) {
        return repository.findByWorkspaceIdAndRecordKey(workspaceId, recordKey)
            .orElseThrow(() -> new IllegalArgumentException("Support guide not found: " + recordKey));
    }

    private void apply(
        SyncGuide guide,
        String title,
        String symptoms,
        String resolution,
        String productArea,
        String severity
    ) {
        guide.setTitle(SyncEntitySupport.requireText(title, "title"));
        guide.setSymptoms(SyncEntitySupport.requireText(symptoms, "symptoms"));
        guide.setResolution(SyncEntitySupport.requireText(resolution, "resolution"));
        guide.setProductArea(SyncEntitySupport.requireText(productArea, "productArea"));
        guide.setSeverity(SyncEntitySupport.requireText(severity, "severity").toUpperCase());
    }
}
