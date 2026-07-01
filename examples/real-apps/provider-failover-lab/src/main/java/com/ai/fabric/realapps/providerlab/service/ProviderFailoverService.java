package com.ai.fabric.realapps.providerlab.service;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ProviderFailoverService {

    private final List<AIProvider> providers;

    public ProviderFailoverService(List<AIProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public ProviderProbeResult runProbe(ProviderProbeRequest request) {
        ProviderProbeRequest effective = request != null
            ? request
            : new ProviderProbeRequest(null, null, null, null);
        List<ProviderAttempt> attempts = new ArrayList<>();
        List<String> orderedProviders = providerOrder(effective);

        for (String providerName : orderedProviders) {
            Optional<AIProvider> provider = providerByName(providerName);
            if (provider.isEmpty() || !provider.get().isAvailable()) {
                attempts.add(new ProviderAttempt(providerName, false, "UNAVAILABLE", "Provider is not available."));
                continue;
            }
            try {
                AIGenerationResponse response = provider.get().generateContent(AIGenerationRequest.builder()
                    .entityType("provider-lab")
                    .entityId("probe")
                    .generationType("diagnostic")
                    .prompt(effective.prompt())
                    .purpose("provider-failover-lab")
                    .build());
                attempts.add(new ProviderAttempt(providerName, true, null, "OK"));
                return new ProviderProbeResult(
                    true,
                    providerName,
                    attempts.size() > 1 ? "PRIMARY_FAILED" : null,
                    response != null ? response.getContent() : "",
                    attempts,
                    diagnostics(effective, response)
                );
            } catch (Exception ex) {
                attempts.add(new ProviderAttempt(providerName, false, "PROVIDER_ERROR", safeMessage(ex)));
            }
        }
        return new ProviderProbeResult(false, null, "NO_PROVIDER_SUCCEEDED", "", attempts, diagnostics(effective, null));
    }

    private List<String> providerOrder(ProviderProbeRequest request) {
        List<String> ordered = new ArrayList<>();
        if (StringUtils.hasText(request.primaryProvider())) {
            ordered.add(request.primaryProvider().trim());
        }
        if (request.fallbackProviders() != null) {
            request.fallbackProviders().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(name -> !ordered.contains(name))
                .forEach(ordered::add);
        }
        if (ordered.isEmpty()) {
            providers.stream().map(AIProvider::getProviderName).forEach(ordered::add);
        }
        return ordered;
    }

    private Optional<AIProvider> providerByName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return providers.stream()
            .filter(provider -> provider.getProviderName() != null)
            .filter(provider -> provider.getProviderName().toLowerCase(Locale.ROOT).equals(normalized))
            .findFirst();
    }

    private Map<String, Object> diagnostics(ProviderProbeRequest request, AIGenerationResponse response) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("providerCount", providers.size());
        diagnostics.put("availableProviders", providers.stream().filter(AIProvider::isAvailable).count());
        diagnostics.put("promptPersisted", false);
        diagnostics.put("rawPromptIncluded", false);
        diagnostics.put("transientFileSeen", StringUtils.hasText(request.transientFileUrl()));
        diagnostics.put("transientFileUrlPersisted", false);
        if (response != null) {
            diagnostics.put("model", response.getModel());
            diagnostics.put("tokensUsed", response.getTokensUsed());
        }
        return Map.copyOf(diagnostics);
    }

    private String safeMessage(Exception ex) {
        String message = ex != null ? ex.getClass().getSimpleName() : "Provider failed";
        return message.length() > 96 ? message.substring(0, 96) : message;
    }

    public record ProviderProbeRequest(
        String prompt,
        String primaryProvider,
        List<String> fallbackProviders,
        String transientFileUrl
    ) {}

    public record ProviderAttempt(String provider, boolean success, String errorCode, String message) {}

    public record ProviderProbeResult(
        boolean success,
        String selectedProvider,
        String fallbackReason,
        String content,
        List<ProviderAttempt> attempts,
        Map<String, Object> diagnostics
    ) {}
}
