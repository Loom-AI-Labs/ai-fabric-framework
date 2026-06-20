package com.ai.fabric.realapps.chat.migration.web;

import com.ai.fabric.realapps.chat.migration.client.RestConnectorVectorClearClient;
import com.ai.fabric.realapps.chat.migration.service.DemoResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoResetAdminControllerTest {

    private final DemoResetService demoResetService = mock(DemoResetService.class);
    private final RestConnectorVectorClearClient vectorClearClient = mock(RestConnectorVectorClearClient.class);
    private final DemoResetAdminController controller = new DemoResetAdminController(demoResetService, vectorClearClient);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "adminAuthEnabled", true);
        ReflectionTestUtils.setField(controller, "adminApiKey", "admin-secret");
        ReflectionTestUtils.setField(controller, "adminApiKeyHeader", "X-ADMIN-API-KEY");
    }

    @Test
    void deniesResetWhenAdminKeyMissing() {
        DemoResetAdminController.ResetRequest request = confirmedResetRequest();

        ResponseEntity<?> response = controller.reset(request, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(demoResetService, never()).clearConnectorData();
        verify(vectorClearClient, never()).clearRuntimeVectors("ecommerce-store-reset");
    }

    @Test
    void deniesEventfulClearWhenAdminKeyDoesNotMatch() {
        DemoResetAdminController.ClearRequest request = confirmedClearRequest();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-ADMIN-API-KEY", "wrong");

        ResponseEntity<?> response = controller.clearEventful(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(demoResetService, never()).clearConnectorDataEventfully();
    }

    @Test
    void allowsResetWhenAdminKeyMatches() {
        DemoResetAdminController.ResetRequest request = confirmedResetRequest();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-ADMIN-API-KEY", "admin-secret");
        when(demoResetService.clearConnectorData()).thenReturn(Map.of("success", true));
        when(vectorClearClient.clearRuntimeVectors("ecommerce-store-reset")).thenReturn(Map.of("success", true));

        ResponseEntity<?> response = controller.reset(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(demoResetService).clearConnectorData();
        verify(vectorClearClient).clearRuntimeVectors("ecommerce-store-reset");
    }

    @Test
    void stillRequiresConfirmationAfterAdminKeyMatches() {
        DemoResetAdminController.ResetRequest request = new DemoResetAdminController.ResetRequest();
        request.setConfirm(false);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-ADMIN-API-KEY", "admin-secret");

        ResponseEntity<?> response = controller.reset(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(demoResetService, never()).clearConnectorData();
        verify(vectorClearClient, never()).clearRuntimeVectors("ecommerce-store-reset");
    }

    private static DemoResetAdminController.ResetRequest confirmedResetRequest() {
        DemoResetAdminController.ResetRequest request = new DemoResetAdminController.ResetRequest();
        request.setConfirm(true);
        return request;
    }

    private static DemoResetAdminController.ClearRequest confirmedClearRequest() {
        DemoResetAdminController.ClearRequest request = new DemoResetAdminController.ClearRequest();
        request.setConfirm(true);
        return request;
    }
}
