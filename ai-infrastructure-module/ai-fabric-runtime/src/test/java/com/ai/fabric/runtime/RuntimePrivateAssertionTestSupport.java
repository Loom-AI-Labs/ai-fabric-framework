package com.ai.fabric.runtime;

import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthIngressMode;
import com.ai.fabric.runtime.auth.RuntimePrivateAssertionService;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

public final class RuntimePrivateAssertionTestSupport {

    private RuntimePrivateAssertionTestSupport() {
    }

    public static RuntimeAuthProperties strictPrivateRuntimeProperties() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        properties.getIngress().getTrustedBackend().setApiKeyValue("runtime-secret");
        properties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-secret");
        properties.getIngress().setAcceptedIssuers(List.of("backend-test", "trusted-backend-app", "platform-poc:SESSION"));
        properties.getIngress().setAcceptedAudiences(List.of("dep-123", "chat-surface", "dep-auth"));
        return properties;
    }

    public static void addPrivateRuntimeHeaders(MockHttpServletRequest request,
                                                RuntimeAuthProperties properties,
                                                RuntimeAuthContext authContext) {
        RuntimePrivateAssertionService service = new RuntimePrivateAssertionService(properties);
        request.addHeader(
            properties.getIngress().getTrustedBackend().getApiKeyHeader(),
            properties.getIngress().getTrustedBackend().getApiKeyValue()
        );
        request.addHeader(
            properties.getIngress().getPrivateAssertions().getAuthorizationHeader(),
            service.tokenScheme() + " " + service.issueAssertion(authContext)
        );
    }
}
