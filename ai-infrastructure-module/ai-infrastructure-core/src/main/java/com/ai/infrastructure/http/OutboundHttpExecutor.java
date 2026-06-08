package com.ai.infrastructure.http;

public interface OutboundHttpExecutor {

    OutboundHttpExecutionResponse execute(OutboundHttpExecutionRequest request);
}
