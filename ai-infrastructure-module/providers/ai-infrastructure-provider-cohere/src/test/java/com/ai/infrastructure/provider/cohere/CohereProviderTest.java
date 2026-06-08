package com.ai.infrastructure.provider.cohere;

import com.ai.infrastructure.dto.AIGenerationInputPart;
import com.ai.infrastructure.dto.AIGenerationInputType;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.http.HttpClient;
import com.ai.infrastructure.provider.ProviderConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CohereProviderTest {

    @Test
    void textFileUrlInputsAreFetchedTransientlyAndSentAsDocuments() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(textResponse(), chatSuccessResponse()));
        CohereProvider provider = new CohereProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .systemPrompt("Return JSON only.")
            .model("command-r7b-12-2024")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-1")
                .fileName("brief.txt")
                .contentType("text/plain")
                .url("https://files.example.com/tmp/brief.txt?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://files.example.com/tmp/brief.txt?sig=secret",
            "https://api.cohere.ai/v1/chat"
        );
        assertThat(httpClient.providerRequestBody())
            .containsEntry("message", "Analyze attached file")
            .containsEntry("prompt_truncation", "AUTO_PRESERVE_ORDER");
        assertThat((String) httpClient.providerRequestBody().get("message"))
            .doesNotContain("Owner brief body");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> documents =
            (List<Map<String, String>>) httpClient.providerRequestBody().get("documents");
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document).containsEntry("id", "doc-1");
            assertThat(document).containsEntry("title", "brief.txt");
            assertThat(document.get("text")).contains("Owner brief body");
        });
        assertThat(response.getContent()).isEqualTo("{\"documentUsage\":[{\"status\":\"USED\"}]}");
        assertThat(response.getMetadata().toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
        assertThat(response.getMetadata().toString()).doesNotContain("sig=secret");
    }

    @Test
    void pdfFileUrlInputsAreFetchedAndExtractedIntoDocuments() throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of(pdfResponse(), chatSuccessResponse()));
        CohereProvider provider = new CohereProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .model("command-r7b-12-2024")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-2")
                .fileName("brief.pdf")
                .contentType("application/pdf")
                .url("https://files.example.com/tmp/brief.pdf?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).containsExactly(
            "https://files.example.com/tmp/brief.pdf?sig=secret",
            "https://api.cohere.ai/v1/chat"
        );
        @SuppressWarnings("unchecked")
        List<Map<String, String>> documents =
            (List<Map<String, String>>) httpClient.providerRequestBody().get("documents");
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document).containsEntry("id", "doc-2");
            assertThat(document).containsEntry("title", "brief.pdf");
            assertThat(document.get("text")).contains("Owner PDF brief body");
            assertThat(document).containsEntry("contentType", "application/pdf");
        });
        assertThat(response.getContent()).isEqualTo("{\"documentUsage\":[{\"status\":\"USED\"}]}");
    }

    @Test
    void unsupportedBinaryFileUrlFailsClosedWithoutFetching() {
        RecordingHttpClient httpClient = new RecordingHttpClient(List.of());
        CohereProvider provider = new CohereProvider(config(), httpClient);

        var response = provider.generateContent(AIGenerationRequest.builder()
            .prompt("Analyze attached file")
            .model("command-r7b-12-2024")
            .inputParts(List.of(AIGenerationInputPart.builder()
                .type(AIGenerationInputType.FILE_URL)
                .documentId("doc-1")
                .fileName("bundle.zip")
                .contentType("application/zip")
                .url("https://files.example.com/tmp/bundle.zip?sig=secret")
                .build()))
            .build());

        assertThat(httpClient.urls()).isEmpty();
        assertThat(response.getStatus()).isEqualTo("PROVIDER_FILE_URL_INPUT_UNSUPPORTED");
        assertThat(response.getContent()).contains("\"status\":\"NOT_USED\"");
        assertThat(response.getContent()).doesNotContain("sig=secret");
    }

    private static ProviderConfig config() {
        return ProviderConfig.builder()
            .providerName("cohere")
            .apiKey("test-key")
            .baseUrl("https://api.cohere.ai/v1")
            .defaultModel("command-r7b-12-2024")
            .defaultEmbeddingModel("embed-english-v3.0")
            .maxTokens(512)
            .temperature(0.2d)
            .timeoutSeconds(30)
            .enabled(true)
            .build();
    }

    private static ResponseEntity<byte[]> textResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return ResponseEntity.ok()
            .headers(headers)
            .body("Owner brief body for product creation.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ResponseEntity<byte[]> pdfResponse() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        return ResponseEntity.ok()
            .headers(headers)
            .body(pdfBytes("Owner PDF brief body for product creation."));
    }

    private static byte[] pdfBytes(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static ResponseEntity<Map> chatSuccessResponse() {
        return ResponseEntity.ok(Map.of(
            "text", "{\"documentUsage\":[{\"status\":\"USED\"}]}",
            "model", "command-r7b-12-2024",
            "meta", Map.of(
                "tokens", Map.of(
                    "input_tokens", 11,
                    "output_tokens", 6
                )
            )
        ));
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final List<ResponseEntity<?>> responses;
        private final List<String> urls = new ArrayList<>();
        private Map<String, Object> providerRequestBody;
        private int index;

        private RecordingHttpClient(List<ResponseEntity<?>> responses) {
            this.responses = responses;
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            urls.add(url);
            if (Map.class.equals(responseType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) requestEntity.getBody();
                providerRequestBody = body;
            }
            @SuppressWarnings("unchecked")
            ResponseEntity<T> casted = (ResponseEntity<T>) responses.get(index++);
            return casted;
        }

        private List<String> urls() {
            return urls;
        }

        private Map<String, Object> providerRequestBody() {
            return providerRequestBody;
        }
    }
}
