package com.ai.fabric.realapps.agenticresolver.config;

import ai.fabric.spi.RAGProvider;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class ResolverPolicyRagIndexer implements CommandLineRunner {

    public static final String POLICY_VECTOR_SPACE = "account-resolution-policy";

    private final AccountResolutionService accountResolutionService;
    private final ObjectProvider<RAGProvider> ragProviderProvider;

    @Override
    public void run(String... args) {
        RAGProvider ragProvider = ragProviderProvider.getIfAvailable();
        if (ragProvider == null) {
            log.debug("RAGProvider not available; skipping account resolver policy indexing");
            return;
        }

        int indexed = 0;
        for (AccountResolutionService.ResolutionPolicy policy : accountResolutionService.policies()) {
            try {
                ragProvider.indexContent(
                    POLICY_VECTOR_SPACE,
                    policy.code(),
                    content(policy),
                    metadata(policy)
                );
                indexed++;
            } catch (Exception ex) {
                log.warn("Failed to index account resolver policy {}", policy.code(), ex);
            }
        }

        log.info("Indexed {} account resolver policy document(s) for RAG", indexed);
    }

    private String content(AccountResolutionService.ResolutionPolicy policy) {
        return """
            Account resolution policy: %s
            Code: %s
            Description: %s
            Recommended action: %s
            Confirmation required: %s
            """.formatted(
            policy.title(),
            policy.code(),
            policy.description(),
            policy.actionName(),
            policy.confirmationRequired()
        ).trim();
    }

    private Map<String, Object> metadata(AccountResolutionService.ResolutionPolicy policy) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", policy.code());
        metadata.put("title", policy.title());
        metadata.put("actionName", policy.actionName());
        metadata.put("confirmationRequired", policy.confirmationRequired());
        return metadata;
    }
}
