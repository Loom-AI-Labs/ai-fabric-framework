package ai.fabric.relationship.it.realapi;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.it.RelationshipQueryIntegrationTestApplication;
import ai.fabric.relationship.it.api.RelationshipQueryRequest;
import ai.fabric.relationship.it.entity.DocumentEntity;
import ai.fabric.relationship.it.entity.UserEntity;
import ai.fabric.relationship.it.repository.DocumentRepository;
import ai.fabric.relationship.it.repository.UserRepository;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.relationship.it.config.BackendEnvTestConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    classes = RelationshipQueryIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("realapi")
@Import(BackendEnvTestConfiguration.class)
class LawFirmRealApiIntegrationTest {

    private static final String QUERY = "Find all contracts related to John Smith in Q4 2023";
    private static final String ARCHIVE_QUERY = "List archived John Smith contracts from October 2023 that mention Archive in the title";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private VectorDatabaseService vectorDatabaseService;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ignored) {
            }
        }
        seedLawFirmData();
    }

    @Test
    void shouldReturnContractsForJohnSmithQ4() {
        RelationshipQueryRequest request = new RelationshipQueryRequest();
        request.setQuery(QUERY);
        request.setEntityTypes(List.of("document"));
        request.setReturnMode(ReturnMode.FULL);
        request.setLimit(5);

        ResponseEntity<RAGResponse> response = restTemplate.postForEntity(
            "/api/relationship-query/execute",
            request,
            RAGResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        RAGResponse rag = response.getBody();
        assertThat(rag.getDocuments()).isNotEmpty();
        assertThat(rag.getDocuments()).anySatisfy(doc -> {
            assertThat(doc.getContent()).contains("Contract - John Smith - Q4 2023");
            assertThat(doc.getMetadata()).isNotNull();
            assertThat(doc.getMetadata().get("author")).isEqualTo("John Smith");
        });
    }

    @Test
    void shouldReturnArchivedContractsForSpecificMonth() {
        RelationshipQueryRequest request = new RelationshipQueryRequest();
        request.setQuery(ARCHIVE_QUERY);
        request.setEntityTypes(List.of("document"));
        request.setReturnMode(ReturnMode.FULL);
        request.setLimit(5);

        ResponseEntity<RAGResponse> response = restTemplate.postForEntity(
            "/api/relationship-query/execute",
            request,
            RAGResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        RAGResponse rag = response.getBody();
        assertThat(rag.getDocuments()).isNotEmpty();
        assertThat(rag.getDocuments()).anySatisfy(doc -> {
            assertThat(doc.getContent()).contains("Contract - John Smith - Q4 2023 (Archive)");
            assertThat(doc.getMetadata()).isNotNull();
            assertThat(doc.getMetadata().get("status")).isEqualTo("ARCHIVED");
            assertThat(doc.getMetadata().get("author")).isEqualTo("John Smith");
        });
    }

    private void seedLawFirmData() {
        UserEntity johnSmith = new UserEntity();
        johnSmith.setFullName("John Smith");
        johnSmith.setEmail("john.smith@firm.test");

        UserEntity janeDoe = new UserEntity();
        janeDoe.setFullName("Jane Doe");
        janeDoe.setEmail("jane.doe@firm.test");

        johnSmith = userRepository.save(johnSmith);
        janeDoe = userRepository.save(janeDoe);

        DocumentEntity q4Contract = createDocument(
            "Contract - John Smith - Q4 2023",
            "ACTIVE",
            johnSmith,
            LocalDateTime.of(2023, 11, 15, 10, 0)
        );
        DocumentEntity q3Contract = createDocument(
            "Contract - John Smith - Q3 2023",
            "ACTIVE",
            johnSmith,
            LocalDateTime.of(2023, 9, 20, 10, 0)
        );
        DocumentEntity archived = createDocument(
            "Contract - John Smith - Q4 2023 (Archive)",
            "ARCHIVED",
            johnSmith,
            LocalDateTime.of(2023, 10, 5, 10, 0)
        );
        DocumentEntity janeContract = createDocument(
            "Contract - Jane Doe - Q4 2023",
            "ACTIVE",
            janeDoe,
            LocalDateTime.of(2023, 11, 8, 10, 0)
        );

        documentRepository.saveAll(List.of(q4Contract, q3Contract, archived, janeContract));
    }

    private DocumentEntity createDocument(String title,
                                          String status,
                                          UserEntity author,
                                          LocalDateTime creationDate) {
        DocumentEntity document = new DocumentEntity();
        document.setTitle(title);
        document.setStatus(status);
        document.setAuthor(author);
        document.setCreationDate(creationDate);
        author.getDocuments().add(document);
        return document;
    }
}
