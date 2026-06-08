package com.ai.infrastructure.prompt;

import com.ai.infrastructure.config.PromptBundleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves prompt templates by applying overlay versions (if configured) then falling back to the base version.
 *
 * <p>Resolution is deterministic: overlays are tried in order, then base.</p>
 */
@Component
@RequiredArgsConstructor
public class PromptTemplateResolver {

    private final PromptTemplateStore store;
    private final PromptBundleProperties bundleProperties;

    private final ConcurrentMap<String, PromptTemplateKey> resolvedKeyCache = new ConcurrentHashMap<>();

    public ResolvedPromptTemplate resolve(String family, String name) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(name, "name must not be null");

        List<String> versions = bundleProperties != null ? bundleProperties.candidateVersions() : List.of("v1");
        if (versions.isEmpty()) {
            throw new IllegalStateException("No prompt bundle versions are configured for family=" + family + " name=" + name);
        }

        String cacheKey = family + ":" + name + ":" + String.join(",", versions);
        PromptTemplateKey cached = resolvedKeyCache.get(cacheKey);
        if (cached != null) {
            return new ResolvedPromptTemplate(store.load(cached), versions);
        }

        for (String version : versions) {
            PromptTemplateKey key = new PromptTemplateKey(family, version, name);
            try {
                PromptTemplate template = store.load(key);
                resolvedKeyCache.putIfAbsent(cacheKey, key);
                return new ResolvedPromptTemplate(template, versions);
            } catch (PromptTemplateNotFoundException ignored) {
                // Missing template for this overlay version; continue to next candidate.
            }
        }

        throw new IllegalStateException("Prompt template not found for family=" + family + " name=" + name + " triedVersions=" + versions);
    }
}
