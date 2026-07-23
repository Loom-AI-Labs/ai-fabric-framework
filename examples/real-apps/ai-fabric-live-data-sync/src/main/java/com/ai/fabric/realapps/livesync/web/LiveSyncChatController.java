package com.ai.fabric.realapps.livesync.web;

import com.ai.fabric.realapps.livesync.service.DemoWorkspaceService;
import com.ai.fabric.realapps.livesync.service.LiveSyncChatService;
import com.ai.fabric.realapps.livesync.web.DemoModels.ChatRequest;
import com.ai.fabric.realapps.livesync.web.DemoModels.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/live-sync")
@RequiredArgsConstructor
public class LiveSyncChatController {

    private final DemoWorkspaceService workspaceService;
    private final LiveSyncChatService chatService;

    @PostMapping("/chat")
    public ChatResponse chat(
        @RequestHeader(DemoWorkspaceService.HEADER) String workspaceId,
        @RequestBody ChatRequest request
    ) {
        workspaceService.requireWorkspace(workspaceId);
        return chatService.query(workspaceId, request);
    }
}
