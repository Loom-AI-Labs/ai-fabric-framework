package com.ai.fabric.realapps.dbactionregistry.service;

import ai.fabric.http.OutboundHttpExecutionRequest;
import ai.fabric.http.OutboundHttpExecutionResponse;
import ai.fabric.http.OutboundHttpExecutor;
import ai.fabric.intent.action.connector.ActionConnectorProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Primary
@Service
public class LocalCustomerConnectorHttpExecutor implements OutboundHttpExecutor {

    private final CustomerTicketActionRuntime runtime;
    private final ObjectMapper objectMapper;

    public LocalCustomerConnectorHttpExecutor(CustomerTicketActionRuntime runtime, ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    @Override
    public OutboundHttpExecutionResponse execute(OutboundHttpExecutionRequest request) {
        try {
            Map<String, Object> body = objectMapper.readValue(
                request != null ? request.body() : "{}",
                new TypeReference<>() {}
            );
            String actionId = text(body.get(ActionConnectorProtocol.KEY_ACTION_ID));
            Map<String, Object> params = map(body.get(ActionConnectorProtocol.KEY_PARAMS));
            Map<String, Object> trace = map(body.get(ActionConnectorProtocol.KEY_TRACE));
            Map<String, Object> response = runtime.execute(actionId, params, trace);
            int status = Boolean.TRUE.equals(response.get(ActionConnectorProtocol.KEY_SUCCESS)) ? 200 : 400;
            return new OutboundHttpExecutionResponse(status, objectMapper.writeValueAsString(response), Map.of());
        } catch (Exception ex) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put(ActionConnectorProtocol.KEY_SUCCESS, false);
            response.put(ActionConnectorProtocol.KEY_ERROR_CODE, "LOCAL_CONNECTOR_ERROR");
            response.put(ActionConnectorProtocol.KEY_MESSAGE, "Local customer connector failed.");
            try {
                return new OutboundHttpExecutionResponse(500, objectMapper.writeValueAsString(response), Map.of());
            } catch (Exception ignored) {
                return new OutboundHttpExecutionResponse(500, "{\"success\":false,\"errorCode\":\"LOCAL_CONNECTOR_ERROR\"}", Map.of());
            }
        }
    }

    private String text(Object raw) {
        return raw != null ? raw.toString().trim() : null;
    }

    private Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                out.put(key.toString(), value);
            }
        });
        return out;
    }
}
