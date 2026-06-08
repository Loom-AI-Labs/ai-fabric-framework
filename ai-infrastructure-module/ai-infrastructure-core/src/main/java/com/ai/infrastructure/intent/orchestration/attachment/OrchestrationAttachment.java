package com.ai.infrastructure.intent.orchestration.attachment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw attachment provided by a client (e.g., UI card selection).
 *
 * <p>This is an input contract only. It must be normalized/validated by the pipeline before use.</p>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationAttachment {

    /**
     * Optional client-provided attachment identifier.
     *
     * <p>When present, this should be a stable identifier for an entity that can drive actions.
     * When absent, the attachment can still provide grounding via {@link #contentText} and/or {@link #metadata}.</p>
     */
    private String id;

    /**
     * Optional knowledge base vector space / entity type for retrieval scoping.
     *
     * <p>This is best-effort; omit when unknown.</p>
     */
    private String vectorSpace;

    /**
     * Optional UI-provided content used for grounding (bounded during normalization).
     */
    private String contentText;

    /**
     * Optional metadata map (scalar-only after normalization).
     */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /**
     * Optional source tag (e.g., "ui-card", "search-result").
     */
    private String source;

    /**
     * Optional URL.
     */
    private String url;

    /**
     * Optional image URL.
     */
    private String imageUrl;
}
