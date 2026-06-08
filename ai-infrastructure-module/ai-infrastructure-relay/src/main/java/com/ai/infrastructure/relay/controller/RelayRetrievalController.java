package com.ai.infrastructure.relay.controller;

import com.ai.infrastructure.relay.api.RetrievalSearchRequestDto;
import com.ai.infrastructure.relay.api.RetrievalSearchResponseDto;
import com.ai.infrastructure.relay.config.RelayProperties;
import com.ai.infrastructure.relay.error.RelayRequestRejectedException;
import com.ai.infrastructure.relay.security.RelayAuthenticator;
import com.ai.infrastructure.relay.service.RelayRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class RelayRetrievalController {

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayAuthenticator authenticator;
    private final RelayRetrievalService retrievalService;

    public RelayRetrievalController(RelayProperties properties,
                                    ObjectMapper objectMapper,
                                    RelayAuthenticator authenticator,
                                    RelayRetrievalService retrievalService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authenticator = authenticator;
        this.retrievalService = retrievalService;
    }

    @PostMapping(path = "/retrieval/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RetrievalSearchResponseDto> search(@RequestHeader Map<String, String> headers,
                                                             @RequestBody String body) {
        try {
            enforceBodySize(body);
            authenticator.verify(headers, body);

            RetrievalSearchRequestDto request = parse(body, RetrievalSearchRequestDto.class);
            RetrievalSearchResponseDto result = retrievalService.search(request);
            return ResponseEntity.ok(result);
        } catch (RelayRequestRejectedException ex) {
            RetrievalSearchResponseDto error = RetrievalSearchResponseDto.failure(ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(ex.getStatus()).body(error);
        } catch (Exception ex) {
            RetrievalSearchResponseDto error = RetrievalSearchResponseDto.failure("SERVICE_UNAVAILABLE", "Relay error.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
