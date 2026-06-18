package ai.fabric.relationship.it.realapi;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.it.RelationshipQueryIntegrationTestApplication;
import ai.fabric.relationship.it.api.RelationshipQueryRequest;
import ai.fabric.relationship.it.config.BackendEnvTestConfiguration;
import ai.fabric.relationship.it.entity.BrandEntity;
import ai.fabric.relationship.it.entity.ProductEntity;
import ai.fabric.relationship.it.repository.BrandRepository;
import ai.fabric.relationship.it.repository.ProductRepository;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = RelationshipQueryIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("realapi")
@Import(BackendEnvTestConfiguration.class)
class ECommerceRealApiIntegrationTest {

    private static final String QUERY = "Show me blue shoes under $100 from Nike";
    private static final String CROSS_BRAND_QUERY = "Show active Nike or Adidas runner shoes priced between $80 and $120 available in red or blue";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired(required = false)
    private VectorDatabaseService vectorDatabaseService;

    @BeforeEach
    void setUp() {
        productRepository.deleteAllInBatch();
        brandRepository.deleteAllInBatch();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ignored) {
            }
        }
        seedCatalog();
    }

    @Test
    void shouldFindBlueNikeShoesUnderHundred() {
        RelationshipQueryRequest request = new RelationshipQueryRequest();
        request.setQuery(QUERY);
        request.setEntityTypes(List.of("product"));
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
        assertThat(rag.getDocuments()).allSatisfy(doc -> {
            assertThat(doc.getMetadata()).isNotNull();
            assertThat(doc.getMetadata().get("brand")).isEqualTo("Nike");
            assertThat(doc.getMetadata().get("status")).isEqualTo("ACTIVE");

            ParsedProduct parsed = parseProductContent(doc.getContent());
            assertThat(parsed.name()).containsIgnoringCase("Blue Runner");
            assertThat(parsed.color()).isEqualTo("blue");
            assertThat(parsed.price()).isLessThan(BigDecimal.valueOf(100));
        });
    }

    @Test
    void shouldFindCrossBrandRunnerShoesWithinRange() {
        RelationshipQueryRequest request = new RelationshipQueryRequest();
        request.setQuery(CROSS_BRAND_QUERY);
        request.setEntityTypes(List.of("product"));
        request.setReturnMode(ReturnMode.FULL);
        request.setLimit(10);

        ResponseEntity<RAGResponse> response = restTemplate.postForEntity(
            "/api/relationship-query/execute",
            request,
            RAGResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        RAGResponse rag = response.getBody();
        assertThat(rag.getDocuments()).isNotEmpty();
        assertThat(rag.getDocuments()).allSatisfy(doc -> {
            assertThat(doc.getMetadata()).isNotNull();
            assertThat(doc.getMetadata().get("brand")).isIn("Nike", "Adidas");
            assertThat(doc.getMetadata().get("status")).isEqualTo("ACTIVE");

            ParsedProduct parsed = parseProductContent(doc.getContent());
            assertThat(parsed.color()).isIn("red", "blue");
            assertThat(parsed.price()).isBetween(BigDecimal.valueOf(80), BigDecimal.valueOf(120));
        });
    }

    private void seedCatalog() {
        BrandEntity nike = new BrandEntity();
        nike.setName("Nike");

        BrandEntity adidas = new BrandEntity();
        adidas.setName("Adidas");

        nike = brandRepository.save(nike);
        adidas = brandRepository.save(adidas);

        ProductEntity nikeBlueRunner = product("Blue Runner Running Shoes", "blue", BigDecimal.valueOf(85), "ACTIVE", nike);
        ProductEntity nikePremiumBoot = product("Premium Trail Boot", "blue", BigDecimal.valueOf(180), "ACTIVE", nike);
        ProductEntity nikeRedRunner = product("Red Runner Running Shoes", "red", BigDecimal.valueOf(90), "ACTIVE", nike);
        ProductEntity adidasBlue = product("Adidas Flex Shoes", "blue", BigDecimal.valueOf(95), "ACTIVE", adidas);
        ProductEntity adidasRunner = product("Adidas Runner Shoes Elite", "red", BigDecimal.valueOf(110), "ACTIVE", adidas);

        productRepository.saveAll(List.of(nikeBlueRunner, nikePremiumBoot, nikeRedRunner, adidasBlue, adidasRunner));
    }

    private ProductEntity product(String name, String color, BigDecimal price, String status, BrandEntity brand) {
        ProductEntity product = new ProductEntity();
        product.setName(name);
        product.setColor(color);
        product.setPrice(price);
        product.setStatus(status);
        product.setBrand(brand);
        brand.getProducts().add(product);
        return product;
    }

    private ParsedProduct parseProductContent(String content) {
        assertThat(content).isNotBlank();

        ParsedProduct parsed = parseProductContentPattern(content);
        if (parsed != null) {
            return parsed;
        }

        String[] tokens = content.trim().split("\\s+");
        assertThat(tokens.length).isGreaterThanOrEqualTo(3);

        String rawColor = tokens[tokens.length - 2].trim();
        String color = rawColor.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);

        String rawPrice = tokens[tokens.length - 1].trim();
        String normalizedPrice = rawPrice.replaceAll("[^0-9.\\-]", "");
        BigDecimal price = new BigDecimal(normalizedPrice);

        String name = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 2)).trim();
        return new ParsedProduct(name, color, price);
    }

    private ParsedProduct parseProductContentPattern(String content) {
        String trimmed = content.trim();

        Pattern parenthesesPattern = Pattern.compile("^(?<name>.*)\\((?<color>[^)]+)\\)\\s*-\\s*\\$?(?<price>[0-9]+(?:\\.[0-9]+)?)\\s*$");
        Matcher parentheses = parenthesesPattern.matcher(trimmed);
        if (parentheses.matches()) {
            String name = parentheses.group("name").trim();
            String color = parentheses.group("color").trim().toLowerCase(Locale.ROOT);
            BigDecimal price = new BigDecimal(parentheses.group("price"));
            return new ParsedProduct(name, color, price);
        }

        Pattern tokensPattern = Pattern.compile("^(?<name>.*)\\s+(?<color>[A-Za-z]+)\\s+\\$?(?<price>[0-9]+(?:\\.[0-9]+)?)\\s*$");
        Matcher tokens = tokensPattern.matcher(trimmed);
        if (tokens.matches()) {
            String name = tokens.group("name").trim();
            String color = tokens.group("color").trim().toLowerCase(Locale.ROOT);
            BigDecimal price = new BigDecimal(tokens.group("price"));
            return new ParsedProduct(name, color, price);
        }

        return null;
    }

    private record ParsedProduct(String name, String color, BigDecimal price) {}
}
