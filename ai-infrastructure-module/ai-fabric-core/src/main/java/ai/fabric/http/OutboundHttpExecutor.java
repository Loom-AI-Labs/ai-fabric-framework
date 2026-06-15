package ai.fabric.http;

public interface OutboundHttpExecutor {

    OutboundHttpExecutionResponse execute(OutboundHttpExecutionRequest request);
}
