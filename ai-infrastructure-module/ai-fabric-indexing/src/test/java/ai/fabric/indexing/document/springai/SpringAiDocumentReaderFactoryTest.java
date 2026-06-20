package ai.fabric.indexing.document.springai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiDocumentReaderFactoryTest {

    private final SpringAiDocumentReaderFactory factory = new SpringAiDocumentReaderFactory();

    @Test
    void createsTextReaderForFilesUnderTrustedRoot(@TempDir Path trustedRoot) throws Exception {
        Path document = trustedRoot.resolve("policy.txt");
        Files.writeString(document, "Trusted policy content");
        SpringAiTrustedResourcePolicy policy = SpringAiTrustedResourcePolicy.trustedRoot(trustedRoot);

        DocumentReader reader = factory.textReader(new FileSystemResource(document), policy);

        List<Document> documents = reader.read();
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getText()).contains("Trusted policy content");
    }

    @Test
    void createsJsonReaderForFilesUnderTrustedRoot(@TempDir Path trustedRoot) throws Exception {
        Path document = trustedRoot.resolve("policy.json");
        Files.writeString(document, """
            {"title":"Refunds","content":"Refunds are available within 30 days."}
            """);
        SpringAiTrustedResourcePolicy policy = SpringAiTrustedResourcePolicy.trustedRoot(trustedRoot);

        DocumentReader reader = factory.jsonReader(new FileSystemResource(document), policy, "content");

        List<Document> documents = reader.read();
        assertThat(documents).isNotEmpty();
        assertThat(documents.getFirst().getText()).contains("Refunds are available within 30 days.");
    }

    @Test
    void rejectsRemoteUrlResourcesBeforeReaderConstruction() throws Exception {
        SpringAiTrustedResourcePolicy policy = SpringAiTrustedResourcePolicy.builder()
            .allowInMemoryResources(true)
            .build();

        assertThatThrownBy(() -> factory.textReader(
            new UrlResource(URI.create("https://example.com/private.txt")),
            policy
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Remote URL");
    }

    @Test
    void rejectsFilesOutsideTrustedRoots(@TempDir Path trustedRoot) throws Exception {
        Path outside = Files.createTempFile("ai-fabric-outside", ".txt");
        Files.writeString(outside, "outside");
        SpringAiTrustedResourcePolicy policy = SpringAiTrustedResourcePolicy.trustedRoot(trustedRoot);

        try {
            assertThatThrownBy(() -> factory.textReader(new FileSystemResource(outside), policy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside trusted roots");
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
