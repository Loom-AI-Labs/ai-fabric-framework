package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.rag.EmbeddingQueryComposer;
import org.springframework.util.StringUtils;

import java.util.Map;

final class InformationRetrievalQuerySupport {

    private InformationRetrievalQuerySupport() {
    }

    static PreparedRetrievalQuery prepare(Intent intent,
                                          PipelineContext pipelineContext,
                                          OrchestrationProperties orchestrationProperties,
                                          boolean deepRetrievalEnabled,
                                          boolean forceRetrievalWhenTargetsPresent,
                                          String optimizedQuery,
                                          String processedQuery,
                                          String retrievalBaseQuery,
                                          String generationQuery,
                                          Map<String, Object> metadata) {
        String retrievalQuery = RetrievalQueryHintSupport.applyRetrievalQueryHint(
            retrievalBaseQuery,
            pipelineContext,
            intent,
            metadata
        );

        // Prefer the LLM-provided optimizedQuery (when present) as the base for the embedding query.
        // The user query may be too short/ambiguous while optimizedQuery carries resolved intent semantics.
        String embeddingBaseQuery = RetrievalQueryHintSupport.applyRetrievalQueryHint(
            retrievalBaseQuery,
            pipelineContext,
            intent,
            null
        );

        boolean forcedTargetResolution = false;
        if (deepRetrievalEnabled
            && forceRetrievalWhenTargetsPresent
            && pipelineContext != null
            && pipelineContext.getResolvedTargets() != null
            && !pipelineContext.getResolvedTargets().isEmpty()
            && intent != null
            && !Boolean.TRUE.equals(intent.getRequiresTargetResolution())) {
            intent.setRequiresTargetResolution(true);
            forcedTargetResolution = true;
        }

        EmbeddingQueryComposer.Result embedding = EmbeddingQueryComposer.compose(
            embeddingBaseQuery,
            intent,
            pipelineContext,
            orchestrationProperties
        );

        if (metadata != null) {
            if (StringUtils.hasText(processedQuery)) {
                metadata.put(EmbeddingQueryComposer.METADATA_KEY_USER_QUERY, processedQuery);
            }
            if (embedding != null && StringUtils.hasText(embedding.embeddingQuery())) {
                metadata.put(EmbeddingQueryComposer.METADATA_KEY_EMBEDDING_QUERY, embedding.embeddingQuery());
            }
            if (embedding != null) {
                metadata.put(EmbeddingQueryComposer.METADATA_KEY_TARGET_HINT_ENABLED, embedding.targetHintEnabled());
                metadata.put(EmbeddingQueryComposer.METADATA_KEY_TARGET_HINT_APPLIED, embedding.targetHintApplied());
                metadata.put(EmbeddingQueryComposer.METADATA_KEY_TARGET_HINT_TARGETS_USED, embedding.targetHintTargetsUsed());
                metadata.put(EmbeddingQueryComposer.METADATA_KEY_TARGET_HINT_CHARS, embedding.targetHintChars());
            }
            if (forcedTargetResolution) {
                metadata.put("requiresTargetResolutionForced", true);
            }
        }

        String advancedDecisionQuery = StringUtils.hasText(optimizedQuery)
            ? optimizedQuery
            : (pipelineContext != null && StringUtils.hasText(pipelineContext.getOriginalQuery())
                ? pipelineContext.getOriginalQuery()
                : generationQuery);

        return new PreparedRetrievalQuery(
            retrievalQuery,
            embedding,
            advancedDecisionQuery,
            forcedTargetResolution
        );
    }

    record PreparedRetrievalQuery(
        String retrievalQuery,
        EmbeddingQueryComposer.Result embedding,
        String advancedDecisionQuery,
        boolean forcedTargetResolution
    ) {
    }
}
