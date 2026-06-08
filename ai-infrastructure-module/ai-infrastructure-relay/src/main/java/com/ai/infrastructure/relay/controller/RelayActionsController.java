package com.ai.infrastructure.relay.controller;

import com.ai.infrastructure.relay.api.ActionExecuteRequestDto;
import com.ai.infrastructure.relay.api.ActionResultDto;
import com.ai.infrastructure.relay.config.RelayProperties;
import com.ai.infrastructure.relay.error.RelayRequestRejectedException;
import com.ai.infrastructure.relay.security.RelayAuthenticator;
import com.ai.infrastructure.relay.service.RelayActionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class RelayActionsController {

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayAuthenticator authenticator;
    private final RelayActionService actionService;

    public RelayActionsController(RelayProperties properties,
                                  ObjectMapper objectMapper,
                                  RelayAuthenticator authenticator,
                                  RelayActionService actionService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authenticator = authenticator;
        this.actionService = actionService;
    }

    @PostMapping(path = "/actions/execute", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionResultDto> execute(@RequestHeader Map<String, String> headers,
                                                   @RequestBody String body) {
        try {
            enforceBodySize(body);
            authenticator.verify(headers, body);

            ActionExecuteRequestDto request = parse(body, ActionExecuteRequestDto.class);
            validate(request);

            ActionResultDto result = actionService.execute(request);
            return ResponseEntity.ok(result);
        } catch (RelayRequestRejectedException ex) {
            ActionResultDto error = ActionResultDto.failure(ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(ex.getStatus()).body(error);
        } catch (Exception ex) {
            ActionResultDto error = ActionResultDto.failure("SERVICE_UNAVAILABLE", "Relay error.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    private void validate(ActionExecuteRequestDto request) {
        if (request == null
            || !StringUtils.hasText(request.actionId())
            || request.params() == null
            || request.trace() == null) {
            throw new RelayRequestRejectedException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "actionId, params, and trace are required.");
        }
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid JSON.");
        }
    }

    private void enforceBodySize(String body) {
        int maxBytes = properties != null && properties.getLimits() != null ? properties.getLimits().getMaxBodyBytes() : 256 * 1024;
        int bytes = body != null ? body.getBytes(StandardCharsets.UTF_8).length : 0;
        if (bytes > maxBytes) {
            throw new RelayRequestRejectedException(HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE", "Request too large.");
        }
    }
}
