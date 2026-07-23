package com.ai.fabric.realapps.livesync.web;

import com.ai.fabric.realapps.livesync.service.DemoMutationService;
import com.ai.fabric.realapps.livesync.service.DemoStateService;
import com.ai.fabric.realapps.livesync.service.DemoWorkspaceService;
import com.ai.fabric.realapps.livesync.service.EntityKind;
import com.ai.fabric.realapps.livesync.service.LiveSyncSearchService;
import com.ai.fabric.realapps.livesync.web.DemoModels.DemoState;
import com.ai.fabric.realapps.livesync.web.DemoModels.EntityUpdateRequest;
import com.ai.fabric.realapps.livesync.web.DemoModels.MutationResponse;
import com.ai.fabric.realapps.livesync.web.DemoModels.SearchRequest;
import com.ai.fabric.realapps.livesync.web.DemoModels.SearchResponse;
import com.ai.fabric.realapps.livesync.web.DemoModels.WorkspaceResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/live-sync")
@RequiredArgsConstructor
public class LiveSyncDemoController {

    private final DemoWorkspaceService workspaceService;
    private final DemoStateService stateService;
    private final DemoMutationService mutationService;
    private final LiveSyncSearchService searchService;

    @PostMapping("/workspaces")
    public WorkspaceResponse createWorkspace() {
        String workspaceId = workspaceService.createWorkspace();
        return new WorkspaceResponse(
            workspaceId,
            workspaceService.expiresAt(workspaceId),
            stateService.state(workspaceId)
        );
    }

    @PostMapping("/reset")
    public WorkspaceResponse reset(
        @RequestHeader(DemoWorkspaceService.HEADER) String workspaceId
    ) {
        workspaceService.resetWorkspace(workspaceId);
        return new WorkspaceResponse(
            workspaceId,
            workspaceService.expiresAt(workspaceId),
            stateService.state(workspaceId)
        );
    }

    @GetMapping("/state")
    public DemoState state(
        @RequestHeader(DemoWorkspaceService.HEADER) String workspaceId
    ) {
        workspaceService.requireWorkspace(workspaceId);
        return stateService.state(workspaceId);
    }

    @PutMapping("/entities/{entityType}/{recordKey}")
    public MutationResponse update(
        @RequestHeader(DemoWorkspaceService.HEADER) String workspaceId,
        @PathVariable String entityType,
        @PathVariable String recordKey,
        @RequestBody EntityUpdateRequest request
    ) {
        return mutationService.update(workspaceId, EntityKind.fromPath(entityType), recordKey, request);
    }

    @DeleteMapping("/entities/{entityType}/{recordKey}")
    public MutationResponse delete(
        @RequestHeader(DemoWorkspaceService.HEADER) String workspaceId,
        @PathVariable String entityType,
        @PathVariable String recordKey
    ) {
        return mutationService.delete(workspaceId, EntityKind.fromPath(entityType), recordKey);
    }

    @PostMapping("/search")
    public SearchResponse search(
        @RequestHeader(DemoWorkspaceService.HEADER) String workspaceId,
        @RequestBody SearchRequest request
    ) {
        workspaceService.requireWorkspace(workspaceId);
        int limit = request != null && request.limit() != null ? request.limit() : 6;
        return searchService.search(workspaceId, request != null ? request.query() : null, limit);
    }

    @GetMapping("/manifest")
    public Map<String, Object> manifest() {
        return Map.of(
            "name", "AI Fabric Live Data Sync",
            "entityTypes", EntityKind.values(),
            "actionsEnabled", false,
            "syncMode", "annotation-driven synchronous create/update/delete",
            "chatContract", "AI Fabric Chat UI v0.3"
        );
    }
}
