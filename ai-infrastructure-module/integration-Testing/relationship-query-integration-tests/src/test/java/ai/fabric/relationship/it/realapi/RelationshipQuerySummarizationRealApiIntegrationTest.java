package ai.fabric.relationship.it.realapi;

import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.relationship.it.RelationshipQueryIntegrationTestApplication;
import ai.fabric.relationship.it.config.BackendEnvTestConfiguration;
import ai.fabric.relationship.it.config.TestRelationshipQueryAccessControlPolicy;
import ai.fabric.relationship.it.entity.BrandEntity;
import ai.fabric.relationship.it.entity.ProductEntity;
import ai.fabric.relationship.it.repository.BrandRepository;
import ai.fabric.relationship.it.repository.ProductRepository;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = RelationshipQueryIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("realapi")
@Import({
    BackendEnvTestConfiguration.class,
    OrchestratorAccessPolicyRealApiIntegrationTest.PolicyConfig.class,
    TestRelationshipQueryAccessControlPolicy.class
})
@TestPropertySource(properties = {
    "ai.infrastructure.relationship.enable-orchestrator-integration=true",
    "ai.infrastructure.relationship.enable-vector-search=false",
    "ai.infrastructure.relationship.fallback-to-vector-search=false",
    "ai.infrastructure.relationship.fallback-to-simple-search=false",
    "ai.relationship-query.post-action-generation.enabled=true",
    "ai.relationship-query.post-action-generation.max-items=10",
    "ai.relationship-query.post-action-generation.max-chars=12000",
    "ai.intent-extraction.progressive.enabled=true"
})
class RelationshipQuerySummarizationRealApiIntegrationTest {

    private static final String QUERY_WITH_SUMMARY = "relationship_query: Show me blue shoes under $100 from Nike and then summarize the results. "
        + "In the summary, list the product name(s) and price(s).";

    @Autowired
    private RAGOrchestrator orchestrator;

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
    void shouldExecuteRelationshipQueryThenGenerateSummaryGroundedInResults() {
        OrchestrationResult result = orchestrator.orchestrate(
            QUERY_WITH_SUMMARY,
            OrchestrationContext.forUser("realapi-user")
        );

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();

        assertThat(result.getMetadata()).containsKey("extractionDiagnostics");

        assertThat(result.getData()).containsKey("actionResult");
        assertThat(result.getData()).containsKey("postActionGeneration");
        assertThat(result.getData()).containsKey("summary");

        Object postAction = result.getData().get("postActionGeneration");
        assertThat(postAction).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> postActionMeta = (Map<String, Object>) postAction;
        assertThat(postActionMeta.get("used")).isEqualTo(true);

        String summary = result.getData().get("summary") != null ? result.getData().get("summary").toString() : null;
        assertThat(summary).isNotBlank();
        assertThat(result.getMessage()).isNotBlank();

        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult).isNotNull();
        assertThat(actionResult.isSuccess()).isTrue();

        Map<String, Object> payload = actionResult.getData().toMap();
        @SuppressWarnings("unchecked")
        List<?> documents = (List<?>) payload.get("documents");
        assertThat(documents).isNotNull().isNotEmpty();

        // Grounding assertion: ensure the generated summary reflects at least one returned document.
        String firstContent = readProperty(documents.getFirst(), "content");
        ParsedProduct parsed = parseProductContent(firstContent);
        assertThat(summary.toLowerCase(Locale.ROOT)).contains(parsed.name().toLowerCase(Locale.ROOT));
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

        productRepository.saveAll(List.of(nikeBlueRunner, nikePremiumBoot, nikeRedRunner, adidasBlue));
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

    private String readProperty(Object target, String property) {
        if (target == null || property == null || property.isBlank()) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            Object value = map.get(property);
            return value != null ? value.toString() : null;
        }
        try {
            String method = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            var reflected = target.getClass().getMethod(method);
            Object value = reflected.invoke(target);
            return value != null ? value.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ParsedProduct(String name, String color, BigDecimal price) {}
}
