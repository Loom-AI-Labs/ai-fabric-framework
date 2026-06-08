package com.ai.fabric.runtime.web;

import com.ai.fabric.runtime.auth.RuntimePublicTokenService;
import com.ai.fabric.runtime.config.RuntimeDeploymentShellConfigService;
import com.ai.fabric.runtime.web.dto.PublicRuntimeSessionBootstrapRequest;
import com.ai.fabric.runtime.web.dto.PublicRuntimeSessionBootstrapResponse;
import com.ai.fabric.runtime.web.dto.RuntimeShellConfigResponse;
import com.ai.fabric.runtime.web.dto.RuntimeShellStarterPromptResponse;
import com.ai.infrastructure.shell.BuiltInShellCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/public/chat")
public class PublicRuntimeSessionController {

    private final RuntimePublicTokenService runtimePublicTokenService;
    private final ObjectProvider<RuntimeDeploymentShellConfigService> deploymentShellConfigServiceProvider;

    public PublicRuntimeSessionController(RuntimePublicTokenService runtimePublicTokenService,
                                          ObjectProvider<RuntimeDeploymentShellConfigService> deploymentShellConfigServiceProvider) {
        this.runtimePublicTokenService = runtimePublicTokenService;
        this.deploymentShellConfigServiceProvider = deploymentShellConfigServiceProvider;
    }

    @PostMapping("/session")
    public ResponseEntity<PublicRuntimeSessionBootstrapResponse> bootstrapSession(
        @RequestBody(required = false) PublicRuntimeSessionBootstrapRequest request,
        jakarta.servlet.http.HttpServletRequest servletRequest
    ) {
        runtimePublicTokenService.authorizeAnonymousBootstrap(servletRequest);
        rejectUnexpectedFields(request);
        RuntimePublicTokenService.IssuedPublicRuntimeToken issued = runtimePublicTokenService.issueAnonymousToken();
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.EXPIRES, "0")
            .body(new PublicRuntimeSessionBootstrapResponse(
                true,
                runtimePublicTokenService.tokenScheme(),
                issued.token(),
                issued.authContext().getAuthMode().name(),
                issued.authContext().getSubjectType().name(),
                issued.authContext().getSessionId(),
                issued.authContext().getDeploymentId(),
                issued.authContext().getCustomerId(),
                issued.authContext().getTenantId(),
                issued.authContext().getGrantedScopes(),
                issued.authContext().getAudiences(),
                issued.authContext().getExpiresAt() == null ? null : issued.authContext().getExpiresAt().toString(),
                toShellConfigResponse()
            ));
    }

    private RuntimeShellConfigResponse toShellConfigResponse() {
        RuntimeDeploymentShellConfigService shellConfigService = deploymentShellConfigServiceProvider.getIfAvailable();
        if (shellConfigService == null) {
            return new RuntimeShellConfigResponse(
                true,
                RuntimeDeploymentShellConfigService.CONTRACT_VERSION,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                BuiltInShellCatalog.MODULE_IDS,
                BuiltInShellCatalog.CARD_IDS,
                BuiltInShellCatalog.EVIDENCE_BLOCK_IDS
            );
        }
        List<RuntimeShellStarterPromptResponse> starterPrompts = shellConfigService.currentStarterPrompts().stream()
            .map(prompt -> new RuntimeShellStarterPromptResponse(
                shellText(prompt, "id"),
                shellText(prompt, "label"),
                shellText(prompt, "query"),
                shellText(prompt, "moduleId"),
                shellText(prompt, "cardId")
            ))
            .toList();
        return new RuntimeShellConfigResponse(
            true,
            shellConfigService.currentContractVersion(),
            shellConfigService.currentGreetingTitle(),
            shellConfigService.currentGreetingMessage(),
            starterPrompts,
            shellConfigService.currentModuleIds(),
            shellConfigService.currentCardIds(),
            BuiltInShellCatalog.MODULE_IDS,
            BuiltInShellCatalog.CARD_IDS,
            BuiltInShellCatalog.EVIDENCE_BLOCK_IDS
        );
    }

    private String shellText(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String value = node.path(field).asText("").trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private void rejectUnexpectedFields(PublicRuntimeSessionBootstrapRequest request) {
        if (request == null || request.getUnexpectedFields().isEmpty()) {
            return;
        }
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Unexpected request fields are not allowed on public runtime bootstrap: "
                + String.join(", ", request.getUnexpectedFields().keySet())
                + ". Runtime issues anonymous session identity."
        );
    }
}
