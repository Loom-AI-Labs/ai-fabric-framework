package ai.fabric.datasync.controller;

import ai.fabric.datasync.dto.DataSyncBatchRequest;
import ai.fabric.datasync.dto.DataSyncBatchResponse;
import ai.fabric.datasync.dto.DataSyncDeleteRequest;
import ai.fabric.datasync.dto.DataSyncOperationResponse;
import ai.fabric.datasync.dto.DataSyncUpsertRequest;
import ai.fabric.datasync.dto.DataSyncVectorSpacesResponse;
import ai.fabric.datasync.service.DataSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSyncControllerTest {

    @Test
    void requestMappingUsesConfigurableBasePathWithDefault() {
        RequestMapping mapping = DataSyncController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping.value()).containsExactly("${ai.data-sync.base-path:/api/ai/data-sync}");
    }

    @Test
    void listVectorSpacesReturnsOkResponse() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncVectorSpacesResponse serviceResponse = new DataSyncVectorSpacesResponse(
            true,
            "OK",
            List.of("document", "product")
        );
        when(service.listVectorSpaces()).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncVectorSpacesResponse> response = controller.listVectorSpaces();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void upsertMapsInvalidRequestToBadRequest() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncOperationResponse serviceResponse = operationFailure("INVALID_REQUEST");
        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        when(service.upsert(request)).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncOperationResponse> response = controller.upsert(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void deleteMapsAccessDeniedToForbidden() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncOperationResponse serviceResponse = operationFailure("ACCESS_DENIED");
        DataSyncDeleteRequest request = new DataSyncDeleteRequest();
        when(service.delete(request)).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncOperationResponse> response = controller.delete(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void batchMapsUnknownVectorSpaceToNotFound() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncBatchResponse serviceResponse = batchFailure("VECTOR_SPACE_NOT_FOUND");
        DataSyncBatchRequest request = new DataSyncBatchRequest();
        when(service.batch(request)).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncBatchResponse> response = controller.batch(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void upsertMapsProjectionRejectionToBadRequest() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncOperationResponse serviceResponse = operationFailure(
            "PROJECTION_REJECTED"
        );
        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        when(service.upsert(request)).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncOperationResponse> response = controller.upsert(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void upsertMapsRetryableIndexingFailureToServiceUnavailable() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncOperationResponse serviceResponse = operationFailure(
            "INDEXING_RETRYABLE"
        );
        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        when(service.upsert(request)).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncOperationResponse> response = controller.upsert(request);

        assertThat(response.getStatusCode()).isEqualTo(
            HttpStatus.SERVICE_UNAVAILABLE
        );
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void upsertMapsPermanentIndexingFailureToInternalServerError() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncOperationResponse serviceResponse = operationFailure(
            "INDEXING_PERMANENT"
        );
        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        when(service.upsert(request)).thenReturn(serviceResponse);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncOperationResponse> response = controller.upsert(request);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void mapsUnexpectedNullServiceResponseToInternalServerError() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncUpsertRequest request = new DataSyncUpsertRequest();
        when(service.upsert(request)).thenReturn(null);
        DataSyncController controller = new DataSyncController(service);

        ResponseEntity<DataSyncOperationResponse> response = controller.upsert(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
    }

    private DataSyncOperationResponse operationFailure(String errorCode) {
        DataSyncOperationResponse response = new DataSyncOperationResponse();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setMessage("failed");
        return response;
    }

    private DataSyncBatchResponse batchFailure(String errorCode) {
        DataSyncBatchResponse response = new DataSyncBatchResponse();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setMessage("failed");
        return response;
    }
}
