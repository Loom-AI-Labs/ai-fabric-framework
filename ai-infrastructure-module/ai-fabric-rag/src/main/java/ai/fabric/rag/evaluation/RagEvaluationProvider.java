package ai.fabric.rag.evaluation;

/** Replaceable scorer. Caller retains identity, data-sharing, spend and request deadlines. */
public interface RagEvaluationProvider {
    String id();
    RagEvaluationResult evaluate(RagEvaluationSample sample);
}
