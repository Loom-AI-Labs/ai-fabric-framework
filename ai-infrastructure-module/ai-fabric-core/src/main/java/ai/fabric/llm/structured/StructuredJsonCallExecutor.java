package ai.fabric.llm.structured;

public interface StructuredJsonCallExecutor {
    <T> StructuredJsonResult<T> execute(StructuredJsonCallSpec<T> spec);
}

