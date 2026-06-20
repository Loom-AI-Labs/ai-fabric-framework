package com.ai.fabric.realapps.chat.migration.web;

import com.ai.fabric.realapps.chat.migration.service.DemoDataResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class MigrationAdminControllerTest {

    private final RecordingDemoDataResetService demoDataResetService = new RecordingDemoDataResetService();
    private final MigrationAdminController controller = new MigrationAdminController(demoDataResetService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "adminAuthEnabled", true);
        ReflectionTestUtils.setField(controller, "adminApiKey", "admin-secret");
        ReflectionTestUtils.setField(controller, "adminApiKeyHeader", "X-ADMIN-API-KEY");
    }

    @Test
    void deniesClearWhenAdminKeyMissing() {
        MigrationAdminController.ClearRequest request = confirmedRequest();

        ResponseEntity<?> response = controller.clear(request, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(demoDataResetService.clearCalls).isZero();
    }

    @Test
    void deniesClearWhenAdminKeyDoesNotMatch() {
        MigrationAdminController.ClearRequest request = confirmedRequest();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-ADMIN-API-KEY", "wrong");

        ResponseEntity<?> response = controller.clear(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(demoDataResetService.clearCalls).isZero();
    }

    @Test
    void allowsClearWhenAdminKeyMatches() {
        MigrationAdminController.ClearRequest request = confirmedRequest();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-ADMIN-API-KEY", "admin-secret");
        DemoDataResetService.ClearResult clearResult = DemoDataResetService.ClearResult.builder()
            .success(true)
            .deleted(Map.of())
            .vectors(Map.of())
            .indexingQueue(Map.of())
            .build();
        demoDataResetService.clearResult = clearResult;

        ResponseEntity<?> response = controller.clear(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(demoDataResetService.clearCalls).isEqualTo(1);
        assertThat(demoDataResetService.lastClearVectors).isTrue();
        assertThat(demoDataResetService.lastClearIndexingQueue).isTrue();
    }

    @Test
    void stillRequiresConfirmationAfterAdminKeyMatches() {
        MigrationAdminController.ClearRequest request = new MigrationAdminController.ClearRequest();
        request.setConfirm(false);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-ADMIN-API-KEY", "admin-secret");

        ResponseEntity<?> response = controller.clear(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(demoDataResetService.clearCalls).isZero();
    }

    private static MigrationAdminController.ClearRequest confirmedRequest() {
        MigrationAdminController.ClearRequest request = new MigrationAdminController.ClearRequest();
        request.setConfirm(true);
        return request;
    }

    private static final class RecordingDemoDataResetService extends DemoDataResetService {
        private int clearCalls;
        private boolean lastClearVectors;
        private boolean lastClearIndexingQueue;
        private ClearResult clearResult = ClearResult.builder()
            .success(true)
            .deleted(Map.of())
            .vectors(Map.of())
            .indexingQueue(Map.of())
            .build();

        private RecordingDemoDataResetService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ClearResult clearDemoData(boolean clearVectors, boolean clearIndexingQueue) {
            clearCalls++;
            lastClearVectors = clearVectors;
            lastClearIndexingQueue = clearIndexingQueue;
            return clearResult;
        }
    }
}
