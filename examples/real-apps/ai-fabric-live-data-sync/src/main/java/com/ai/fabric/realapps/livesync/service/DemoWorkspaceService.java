package com.ai.fabric.realapps.livesync.service;

import com.ai.fabric.realapps.livesync.domain.DemoWorkspace;
import com.ai.fabric.realapps.livesync.repository.DemoWorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DemoWorkspaceService {

    public static final String HEADER = "X-Demo-Workspace-ID";

    private final DemoWorkspaceRepository workspaceRepository;
    private final SyncProductService productService;
    private final SyncPolicyService policyService;
    private final SyncGuideService guideService;
    private final DemoSeedService seedService;
    private final SyncAuditService auditService;
    private final ControlledIndexAnalysisHandler analysisHandler;

    @Value("${app.demo.workspace.ttl:PT6H}")
    private Duration workspaceTtl;

    @Transactional
    public synchronized String createWorkspace() {
        String workspaceId = "sync-demo-" + UUID.randomUUID();
        Instant now = Instant.now();
        DemoWorkspace workspace = new DemoWorkspace();
        workspace.setId(workspaceId);
        workspace.setCreatedAt(now);
        workspace.setLastTouchedAt(now);
        workspaceRepository.saveAndFlush(workspace);
        seedService.seed(workspaceId);
        return workspaceId;
    }

    @Transactional
    public synchronized void resetWorkspace(String workspaceId) {
        requireWorkspace(workspaceId);
        deleteWorkspaceEntities(workspaceId);
        auditService.clear(workspaceId);
        analysisHandler.clearWorkspace(workspaceId);
        seedService.seed(workspaceId);
        touch(workspaceId);
    }

    @Transactional
    public DemoWorkspace requireWorkspace(String workspaceId) {
        if (!StringUtils.hasText(workspaceId)) {
            throw new IllegalArgumentException(HEADER + " is required");
        }
        DemoWorkspace workspace = workspaceRepository.findById(workspaceId.trim())
            .orElseThrow(() -> new IllegalArgumentException("Demo workspace was not found or has expired"));
        workspace.setLastTouchedAt(Instant.now());
        return workspaceRepository.save(workspace);
    }

    @Transactional
    public void touch(String workspaceId) {
        requireWorkspace(workspaceId);
    }

    public Instant expiresAt(String workspaceId) {
        DemoWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new IllegalArgumentException("Demo workspace was not found or has expired"));
        return workspace.getLastTouchedAt().plus(workspaceTtl);
    }

    @Scheduled(cron = "${app.demo.workspace.cleanup-cron:0 23 * * * *}")
    @Transactional
    public synchronized void cleanupExpiredWorkspaces() {
        Instant cutoff = Instant.now().minus(workspaceTtl);
        List<DemoWorkspace> expired = workspaceRepository.findAllByLastTouchedAtBefore(cutoff);
        for (DemoWorkspace workspace : expired) {
            deleteWorkspaceEntities(workspace.getId());
            workspaceRepository.delete(workspace);
            auditService.clear(workspace.getId());
            analysisHandler.clearWorkspace(workspace.getId());
        }
    }

    private void deleteWorkspaceEntities(String workspaceId) {
        productService.findAll(workspaceId)
            .forEach(product -> productService.deleteProduct(workspaceId, product.getRecordKey()));
        policyService.findAll(workspaceId)
            .forEach(policy -> policyService.deletePolicy(workspaceId, policy.getRecordKey()));
        guideService.findAll(workspaceId)
            .forEach(guide -> guideService.deleteGuide(workspaceId, guide.getRecordKey()));
    }
}
