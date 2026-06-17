package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.action.ActionListPayload;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagResultSummarySupport {

    private RagResultSummarySupport() {
    }

    static boolean isEmptyActionResultPayload(ActionPayload data) {
        if (data instanceof ActionListPayload listPayload) {
            return listPayload.isEmpty();
        }
        return false;
    }

    static List<String> extractVectorSpacesUsed(OrchestrationResult ragResult,
                                                String fallbackVectorSpace,
                                                String candidateVectorSpacesKey) {
        if (ragResult != null && ragResult.getData() instanceof Map<?, ?> map) {
            Object candidates = map.get(candidateVectorSpacesKey);
            if (candidates instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    if (item != null && StringUtils.hasText(item.toString())) {
                        out.add(item.toString());
                    }
                }
                if (!out.isEmpty()) {
                    return Collections.unmodifiableList(out);
                }
            }
        }
        if (StringUtils.hasText(fallbackVectorSpace)) {
            return RagContextSupport.parseVectorSpaces(fallbackVectorSpace);
        }
        return List.of();
    }

    static Map<String, Object> summarizeRagResult(OrchestrationResult ragResult) {
        if (ragResult == null) {
            return Map.of(
                "type", OrchestrationResultType.ERROR.name(),
                "success", false,
                "message", "RAG fallback returned null result",
                "data", Map.of()
            );
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", ragResult.getType() != null ? ragResult.getType().name() : null);
        summary.put("success", ragResult.isSuccess());
        summary.put("message", ragResult.getMessage());
        summary.put("data", ragResult.getData() != null ? ragResult.getData() : Map.of());
        return Collections.unmodifiableMap(summary);
    }

    static String extractAnswer(OrchestrationResult ragResult, String answerKey) {
        if (ragResult == null || ragResult.getType() != OrchestrationResultType.INFORMATION_PROVIDED || !ragResult.isSuccess()) {
            return null;
        }
        if (ragResult.getData() instanceof Map<?, ?> map) {
            Object value = map.get(answerKey);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString();
            }
        }
        return null;
    }

    static boolean isNoEvidenceRagResult(OrchestrationResult result,
                                         String documentsKey,
                                         String ragResponseKey,
                                         String confidenceScoreKey,
                                         String noContextMessage) {
        if (result == null || result.getData() == null) {
            return false;
        }
        if (!(result.getData() instanceof Map<?, ?> data)) {
            return false;
        }

        int docsCount = 0;
        Object docsObj = data.get(documentsKey);
        if (docsObj instanceof List<?> list) {
            docsCount = list.size();
        }

        String ctx = null;
        Object ragObj = data.get(ragResponseKey);
        if (ragObj instanceof RAGResponse rag) {
            ctx = rag.getContext();
            if (rag.getDocuments() != null && !rag.getDocuments().isEmpty()) {
                docsCount = Math.max(docsCount, rag.getDocuments().size());
            }
        } else if (ragObj != null) {
            ctx = ragObj.toString();
        }

        Double confidence = null;
        Object confObj = data.get(confidenceScoreKey);
        if (confObj instanceof Number n) {
            confidence = n.doubleValue();
        }

        boolean noDocs = docsCount <= 0;
        boolean noContext = !StringUtils.hasText(ctx) || noContextMessage.equals(ctx);
        boolean lowConfidence = confidence == null || confidence <= 0.0d;
        return noDocs && noContext && lowConfidence;
    }
}
