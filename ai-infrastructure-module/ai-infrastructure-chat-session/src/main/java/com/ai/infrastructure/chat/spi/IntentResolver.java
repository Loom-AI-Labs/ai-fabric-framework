package com.ai.infrastructure.chat.spi;

import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import java.util.Map;

/**
 * SPI for resolving conversation-specific intent scenarios (e.g., action confirmations).
 *
 * <p>Resolvers are evaluated in priority order (lower = higher priority). The first resolver that
 * returns {@code true} from {@link #canResolve(MultiIntentResponse, Map, PipelineContext)} is used.</p>
 */
public interface IntentResolver {

    boolean canResolve(MultiIntentResponse intentResponse,
                       Map<String, Object> sessionMetadata,
                       PipelineContext context);

    PipelineContext resolve(MultiIntentResponse intentResponse,
                            Map<String, Object> sessionMetadata,
                            PipelineContext context);

    default int getPriority() {
        return 100;
    }

    String getResolverName();
}

