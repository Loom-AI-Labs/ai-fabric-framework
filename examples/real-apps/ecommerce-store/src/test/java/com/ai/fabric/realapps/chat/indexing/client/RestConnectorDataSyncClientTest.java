package com.ai.fabric.realapps.chat.indexing.client;

import com.ai.fabric.realapps.chat.indexing.ConnectorIndexingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestConnectorDataSyncClientTest {

    @Test
    void upsertProductSendsVerifiedPlatformAuthContext() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestConnectorDataSyncClient client = new RestConnectorDataSyncClient(
            restTemplate,
            new ConnectorIndexingProperties(true, "http://runtime.local", "", "X-AIFABRIC-API-KEY")
        );

        server.expect(requestTo("http://runtime.local/api/ai/data-sync/upsert"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.vectorSpace").value("product"))
            .andExpect(jsonPath("$.id").value("SKU-0001"))
            .andExpect(jsonPath("$.entity.name").value("Premium Wireless Headphones"))
            .andExpect(jsonPath("$.trace.requestId").exists())
            .andExpect(jsonPath("$.trace.metadata.source").value("ecommerce-store"))
            .andExpect(jsonPath("$.trace.authContext.subjectId").value("system:platform-ecommerce-store"))
            .andExpect(jsonPath("$.trace.authContext.subjectType").value("SYSTEM_PROCESS"))
            .andExpect(jsonPath("$.trace.authContext.authMode").value("PRIVATE_RUNTIME_BACKEND_MEDIATED"))
            .andExpect(jsonPath("$.trace.authContext.callerType").value("SYSTEM_PROCESS"))
            .andExpect(jsonPath("$.trace.authContext.deploymentId").value("ecommerce-store-local"))
            .andExpect(jsonPath("$.trace.authContext.issuer").value("platform-ecommerce-store"))
            .andExpect(jsonPath("$.trace.authContext.grantedScopes[0]").value("data-sync:upsert"))
            .andExpect(jsonPath("$.trace.authContext.grantedScopes[1]").value("data-sync:delete"))
            .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        client.upsertProduct(Map.of(
            "sku", "SKU-0001",
            "name", "Premium Wireless Headphones",
            "description", "Noise-cancelling over-ear headphones",
            "category", "Headphones",
            "price", BigDecimal.valueOf(199.99)
        ));

        server.verify();
    }

    @Test
    void deleteProductSendsDeleteRequestWithVerifiedPlatformAuthContext() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        RestConnectorDataSyncClient client = new RestConnectorDataSyncClient(
            restTemplate,
            new ConnectorIndexingProperties(true, "http://runtime.local/", "", "X-AIFABRIC-API-KEY")
        );

        server.expect(requestTo("http://runtime.local/api/ai/data-sync/delete"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.vectorSpace").value("product"))
            .andExpect(jsonPath("$.id").value("SKU-0001"))
            .andExpect(jsonPath("$.trace.metadata.operation").value("product-delete"))
            .andExpect(jsonPath("$.trace.authContext.subjectId").value("system:platform-ecommerce-store"))
            .andExpect(jsonPath("$.trace.authContext.grantedScopes[1]").value("data-sync:delete"))
            .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        client.deleteProductBySku("SKU-0001");

        server.verify();
    }
}
