package com.ai.infrastructure.intent.vectorspace;

import com.ai.infrastructure.dto.Intent;

/**
 * Routes retrieval intents to a vectorSpace when the extractor omits it.
 */
public interface VectorSpaceRouter {

    /**
     * Route an intent to a vector space (single or multiple candidates).
     *
     * @param intent intent requiring retrieval
     * @param originalQuery original user query
     * @return routing result
     */
    RoutingResult route(Intent intent, String originalQuery);
}

