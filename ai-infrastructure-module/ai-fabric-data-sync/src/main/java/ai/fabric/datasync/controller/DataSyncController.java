package ai.fabric.datasync.controller;

import ai.fabric.datasync.dto.DataSyncBatchRequest;
import ai.fabric.datasync.dto.DataSyncBatchResponse;
import ai.fabric.datasync.dto.DataSyncDeleteRequest;
import ai.fabric.datasync.dto.DataSyncOperationResponse;
import ai.fabric.datasync.dto.DataSyncUpsertRequest;
import ai.fabric.datasync.dto.DataSyncVectorSpacesResponse;
import ai.fabric.datasync.service.DataSyncService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Push-based data sync API for managed vector databases.
 */
@RestController
@RequestMapping("${ai.data-sync.base-path:/api/ai/data-sync}")
@ConditionalOnProperty(prefix = "ai.data-sync", name = "enabled", havingValue = "true")
@ConditionalOnBean(DataSyncService.class)
public class DataSyncController {

    private static final Logger log = LoggerFactory.getLogger(DataSyncController.class);

    private final DataSyncService service;

    public DataSyncController(DataSyncService service) {
        this.service = service;
    }

    @GetMapping("/vector-spaces")
    public ResponseEntity<DataSyncVectorSpacesResponse> listVectorSpaces() {
        return ResponseEntity.ok(service.listVectorSpaces());
    }

    @PostMapping("/upsert")
    public ResponseEntity<DataSyncOperationResponse> upsert(@Valid @RequestBody DataSyncUpsertRequest request) {
        String requestId = request != null && request.getTrace() != null ? request.getTrace().getRequestId() : null;
        log.debug("Data sync upsert requested (vectorSpace={}, id={}, requestId={})",
            request != null ? request.getVectorSpace() : null,
            request != null ? request.getId() : null,
            requestId);

        DataSyncOperationResponse response = service.upsert(request);
        return respond(response != null ? response.getErrorCode() : null, response);
    }

    @PostMapping("/delete")
    public ResponseEntity<DataSyncOperationResponse> delete(@Valid @RequestBody DataSyncDeleteRequest request) {
        String requestId = request != null && request.getTrace() != null ? request.getTrace().getRequestId() : null;
        log.debug("Data sync delete requested (vectorSpace={}, id={}, requestId={})",
            request != null ? request.getVectorSpace() : null,
            request != null ? request.getId() : null,
            requestId);

        DataSyncOperationResponse response = service.delete(request);
        return respond(response != null ? response.getErrorCode() : null, response);
    }

    @PostMapping("/batch")
    public ResponseEntity<DataSyncBatchResponse> batch(@Valid @RequestBody DataSyncBatchRequest request) {
        String requestId = request != null && request.getTrace() != null ? request.getTrace().getRequestId() : null;
        int size = request != null && request.getOperations() != null ? request.getOperations().size() : 0;
        log.debug("Data sync batch requested (ops={}, requestId={})", size, requestId);

        DataSyncBatchResponse response = service.batch(request);
        return respond(response != null ? response.getErrorCode() : null, response);
    }

    private <T> ResponseEntity<T> respond(String errorCode, T body) {
        if (body == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (errorCode == null || errorCode.isBlank()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(mapStatus(errorCode.trim())).body(body);
    }

    private HttpStatus mapStatus(String errorCode) {
        return switch (errorCode) {
            case "ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "VECTOR_SPACE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_REQUEST",
                 "BATCH_TOO_LARGE",
                 "VECTOR_SPACE_NOT_INDEXABLE",
                 "PROJECTION_REJECTED" -> HttpStatus.BAD_REQUEST;
            case "INDEXING_RETRYABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INDEXING_PERMANENT" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
