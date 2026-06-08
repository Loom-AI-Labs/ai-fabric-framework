package com.ai.infrastructure.config;

import com.ai.infrastructure.dto.AIEntityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.Resource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AIEntityConfigurationLoaderUrlTest {

    @Test
    void loadConfigurationFromHttpUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ai-entity-config.yml", exchange -> {
            byte[] body = """
                ai-config:
                  vector-dimensions: 512
                ai-entities:
                  product:
                    searchable-fields:
                      - name: title
                    embeddable-fields:
                      - name: description
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/yaml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ai-entity-config.yml";
            AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(new DefaultResourceLoader());

            loader.loadConfigurationFromFile(url);

            AIEntityConfig config = loader.getEntityConfig("product");
            assertThat(config).isNotNull();
            assertThat(config.getSearchableFields()).extracting("name").containsExactly("title");
            assertThat(config.getEmbeddableFields()).extracting("name").containsExactly("description");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loadConfigurationFromResourceThatReportsMissingButStreamsSuccessfully() {
        Resource resource = new ByteArrayResource("""
            ai-entities:
              product:
                searchable-fields:
                  - name: title
            """.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public boolean exists() {
                return false;
            }
        };
        ResourceLoader resourceLoader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };

        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(resourceLoader);

        loader.loadConfigurationFromFile("https://platform.example/api/deployments/dep/versions/ver/artifacts/ai-entity-config.yml");

        AIEntityConfig config = loader.getEntityConfig("product");
        assertThat(config).isNotNull();
        assertThat(config.getSearchableFields()).extracting("name").containsExactly("title");
    }
}
