package com.ai.infrastructure.prompt;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from classpath resources under {@code prompts/...}.
 */
@Component
@RequiredArgsConstructor
public class ClasspathPromptTemplateStore implements PromptTemplateStore {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final ResourceLoader resourceLoader;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    @Override
    public PromptTemplate load(PromptTemplateKey key) {
        Objects.requireNonNull(key, "key must not be null");
        String id = key.id();
        return cache.computeIfAbsent(id, ignored -> loadUncached(key));
    }

    private PromptTemplate loadUncached(PromptTemplateKey key) {
        String path = key.resourcePath();
        Resource resource = resourceLoader.getResource(CLASSPATH_PREFIX + path);
        if (!resource.exists()) {
            throw new PromptTemplateNotFoundException("Prompt template not found on classpath: " + path);
        }

        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new PromptTemplate(key, content);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read prompt template: " + path, ex);
        }
    }
}
