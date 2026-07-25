package ai.fabric.indexing.worker;

import ai.fabric.core.AICoreService;
import ai.fabric.indexing.api.AIIndexAnalysisHandler;
import ai.fabric.indexing.model.AIIndexDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default explicit analysis handler operating only on the approved projection.
 */
public class DefaultAIIndexAnalysisHandler implements AIIndexAnalysisHandler {

    private final AICoreService coreService;
    private final ObjectMapper objectMapper;

    public DefaultAIIndexAnalysisHandler(
        AICoreService coreService,
        ObjectMapper objectMapper
    ) {
        this.coreService = Objects.requireNonNull(coreService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public String analyze(AIIndexDocument document) {
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("entityType", document.entityType());
        approved.put("semanticText", document.semanticSearchText());
        approved.put("ragContext", document.ragContextText());
        approved.put("context", document.llmContext());
        try {
            String payload = objectMapper.writeValueAsString(approved);
            return coreService.generateText("""
                Analyze the following approved AI entity projection.
                Return a concise factual analysis. Do not infer or expose data outside the payload.

                %s
                """.formatted(payload));
        } catch (JsonProcessingException exception) {
            throw new IndexingExecutionException(
                "ANALYSIS_INPUT_SERIALIZATION_FAILED",
                exception
            );
        }
    }
}
