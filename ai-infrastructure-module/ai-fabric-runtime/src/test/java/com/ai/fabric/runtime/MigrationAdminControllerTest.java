package com.ai.fabric.runtime;

import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimePrivateAssertionService;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.ai.fabric.runtime.web.admin.MigrationAdminController;
import com.ai.fabric.runtime.web.admin.RuntimeAdminScopeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationAdminControllerTest {

    @Test
    void clearReturnsServiceUnavailableWhenVectorDatabaseServiceIsMissing() {
        RuntimeAuthProperties authProperties = new RuntimeAuthProperties();
        authProperties.getIngress().getTrustedBackend().setApiKeyValue("runtime-secret");
        authProperties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-secret");
        MigrationAdminController controller = new MigrationAdminController(
            new EmptyObjectProvider<>(),
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null)
        );

        MigrationAdminController.ClearRequest request = new MigrationAdminController.ClearRequest();
        request.setConfirm(true);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        RuntimePrivateAssertionService privateAssertionService = new RuntimePrivateAssertionService(authProperties);
        RuntimeAuthContext authContext = RuntimeAuthContext.builder()
            .subjectId("platform-admin")
            .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
            .authMode(RuntimeAuthMode.PLATFORM_PROXY_SESSION)
            .callerType(RuntimeAuthCallerType.PLATFORM_PROXY)
            .deploymentId("dep-runtime")
            .sessionId("migration-admin-test")
            .issuer("platform-runtime:SESSION")
            .audiences(List.of("dep-runtime"))
            .expiresAt(Instant.now().plusSeconds(300))
            .grantedScopes(List.of(RuntimeAdminScopeCatalog.RUNTIME_MIGRATION_CLEAR))
            .build();
        httpRequest.addHeader("X-AIFABRIC-RUNTIME-API-KEY", "runtime-secret");
        httpRequest.addHeader(
            privateAssertionService.authorizationHeaderName(),
            privateAssertionService.tokenScheme() + " " + privateAssertionService.issueAssertion(authContext)
        );

        var response = controller.clear(null, null, request, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isInstanceOfAny(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) response.getBody()).get("message"))
            .isEqualTo("No VectorDatabaseService is configured for this runtime.");
    }

    private static final class EmptyObjectProvider<T> implements ObjectProvider<T> {

        @Override
        public T getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }

        @Override
        public T getObject() {
            throw new UnsupportedOperationException();
        }
    }
}
