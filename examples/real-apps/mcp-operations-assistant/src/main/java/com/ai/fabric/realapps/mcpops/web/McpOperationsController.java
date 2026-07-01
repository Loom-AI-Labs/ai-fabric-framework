package com.ai.fabric.realapps.mcpops.web;

import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mcp-ops")
@RequiredArgsConstructor
public class McpOperationsController {

    private final McpOperationsService service;

    @GetMapping("/tools")
    public List<McpOperationsService.ToolPolicy> tools() {
        return service.catalog();
    }

    @PostMapping("/tools/execute")
    public McpOperationsService.ToolExecutionResult execute(
        @RequestBody McpOperationsService.ToolExecutionRequest request
    ) {
        return service.execute(request);
    }
}
