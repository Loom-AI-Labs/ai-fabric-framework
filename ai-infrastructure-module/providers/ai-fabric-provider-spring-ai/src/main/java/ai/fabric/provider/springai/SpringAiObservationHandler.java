package ai.fabric.provider.springai;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

public class SpringAiObservationHandler implements ObservationHandler<Observation.Context> {

    private final SpringAiObservationDiagnostics diagnostics;

    public SpringAiObservationHandler(SpringAiObservationDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public void onStart(Observation.Context context) {
        diagnostics.recordStart(context);
    }

    @Override
    public void onStop(Observation.Context context) {
        diagnostics.recordStop(context);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatClientObservationContext
            || context instanceof ChatModelObservationContext
            || context instanceof EmbeddingModelObservationContext
            || context instanceof AdvisorObservationContext
            || context instanceof ToolCallingObservationContext;
    }
}
