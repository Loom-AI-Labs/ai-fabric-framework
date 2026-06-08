package com.ai.fabric.runtime;

import com.ai.fabric.runtime.admin.RuntimeConnectorAdminProxyService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConnectorAdminProxyServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsConnectorAdminGetRequestsWithConfiguredApiKey() throws Exception {
        AtomicReference<String> observedPath = new AtomicReference<>();
        AtomicReference<String> observedApiKey = new AtomicReference<>();
        AtomicReference<String> observedAdminApiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/admin/overview", exchange -> {
            observedPath.set(exchange.getRequestURI().getPath());
            observedApiKey.set(exchange.getRequestHeaders().getFirst("X-AIFABRIC-API-KEY"));
            observedAdminApiKey.set(exchange.getRequestHeaders().getFirst("X-ADMIN-API-KEY"));
            writeJson(exchange, 200, "{\"success\":true,\"surface\":\"connector-overview\"}");
        });
        server.start();

        RuntimeConnectorAdminProxyService service = instantiateService(
            "http://localhost:" + server.getAddress().getPort(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            "X-AIFABRIC-API-KEY",
            "connector-secret",
            null,
            null
        );
        Object response = service.forwardGet("/api/admin/overview");

        assertThat(proxyResponseValue(response, "status")).isEqualTo(200);
        assertThat(proxyResponseValue(response, "contentType").toString()).contains("application/json");
        assertThat(proxyResponseValue(response, "body").toString()).contains("\"surface\":\"connector-overview\"");
        assertThat(observedPath.get()).isEqualTo("/api/admin/overview");
        assertThat(observedApiKey.get()).isEqualTo("connector-secret");
        assertThat(observedAdminApiKey.get()).isNull();
    }

    @Test
    void prefersDedicatedConnectorAdminApiKeyWhenConfigured() throws Exception {
        AtomicReference<String> observedApiKey = new AtomicReference<>();
        AtomicReference<String> observedAdminApiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/admin/overview", exchange -> {
            observedApiKey.set(exchange.getRequestHeaders().getFirst("X-AIFABRIC-API-KEY"));
            observedAdminApiKey.set(exchange.getRequestHeaders().getFirst("X-ADMIN-API-KEY"));
            writeJson(exchange, 200, "{\"success\":true,\"surface\":\"connector-overview\"}");
        });
        server.start();

        RuntimeConnectorAdminProxyService service = instantiateService(
            "http://localhost:" + server.getAddress().getPort(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            "X-AIFABRIC-API-KEY",
            "connector-secret",
            "X-ADMIN-API-KEY",
            "admin-secret"
        );

        Object response = service.forwardGet("/api/admin/overview");

        assertThat(proxyResponseValue(response, "status")).isEqualTo(200);
        assertThat(observedApiKey.get()).isNull();
        assertThat(observedAdminApiKey.get()).isEqualTo("admin-secret");
    }

    @Test
    void returnsServiceUnavailableWhenConnectorBaseUrlIsMissing() throws Exception {
        RuntimeConnectorAdminProxyService service = instantiateService(
            null,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            "X-AIFABRIC-API-KEY",
            "connector-secret",
            null,
            null
        );

        Object response = service.forwardGet("/api/admin/overview");

        assertThat(proxyResponseValue(response, "status")).isEqualTo(503);
        assertThat(proxyResponseValue(response, "body").toString()).contains("baseUrl is not configured");
    }

    private RuntimeConnectorAdminProxyService instantiateService(String baseUrl,
                                                                 Duration connectTimeout,
                                                                 Duration readTimeout,
                                                                 String apiKeyHeader,
                                                                 String apiKeyValue,
                                                                 String adminApiKeyHeader,
                                                                 String adminApiKeyValue) throws Exception {
        Class<?> propertiesClass = Class.forName("com.ai.infrastructure.intent.action.connector.AIActionConnectorProperties");
        Object properties = propertiesClass.getDeclaredConstructor().newInstance();
        propertiesClass.getMethod("setBaseUrl", String.class).invoke(properties, baseUrl);
        propertiesClass.getMethod("setConnectTimeout", Duration.class).invoke(properties, connectTimeout);
        propertiesClass.getMethod("setReadTimeout", Duration.class).invoke(properties, readTimeout);
        Object apiKey = propertiesClass.getMethod("getApiKey").invoke(properties);
        apiKey.getClass().getMethod("setHeader", String.class).invoke(apiKey, apiKeyHeader);
        apiKey.getClass().getMethod("setValue", String.class).invoke(apiKey, apiKeyValue);
        Object adminApiKey = propertiesClass.getMethod("getAdmin").invoke(properties);
        if (adminApiKeyHeader != null) {
            adminApiKey.getClass().getMethod("setHeader", String.class).invoke(adminApiKey, adminApiKeyHeader);
        }
        if (adminApiKeyValue != null) {
            adminApiKey.getClass().getMethod("setValue", String.class).invoke(adminApiKey, adminApiKeyValue);
        }

        return (RuntimeConnectorAdminProxyService) RuntimeConnectorAdminProxyService.class
            .getDeclaredConstructors()[0]
            .newInstance(properties);
    }

    private Object proxyResponseValue(Object response, String accessorName) throws Exception {
        return response.getClass().getMethod(accessorName).invoke(response);
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
