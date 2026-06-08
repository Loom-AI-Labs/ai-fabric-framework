package com.ai.infrastructure.rag.source;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class ResolvedKnowledgeSource {

    String id;
    String type;
    String adapterType;
    String attributionLabel;
    String entityType;
    @Builder.Default
    Map<String, Object> filters = Map.of();
    @Builder.Default
    List<String> authModes = List.of();
    String handleRef;
    @Builder.Default
    boolean enabled = true;
}
