package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorAIActionHandlerTest {

    @Test
    void shouldRenderConfirmationTemplateWithFallbackPlaceholder() {
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            AIActionMetaData.builder()
                .name("commerce_update_cart")
                .category("commerce")
                .accessMode(ActionAccessMode.WRITE_ONLY)
                .build(),
            true,
            "{{cart_update_confirmation|Update your cart}}?",
            Set.of(),
            null
        );

        assertThat(handler.getConfirmationMessage(
            Map.of("cart_update_confirmation", "Add 1 Selling Plans Ski Wax to your cart"),
            null
        )).isEqualTo("Add 1 Selling Plans Ski Wax to your cart?");
        assertThat(handler.getConfirmationMessage(Map.of(), null)).isEqualTo("Update your cart?");
    }

    @Test
    void executeAction_shouldReturnStructuredFailureWhenAccessModeIsMissing() {
        ActionConnectorExecutor executor = new ActionConnectorExecutor(
            new AIActionConnectorProperties(),
            request -> {
                throw new AssertionError("executor should not be called when metadata is incomplete");
            },
            null,
            Clock.systemUTC()
        );
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            AIActionMetaData.builder()
                .name("broken_action")
                .category("generic")
                .build(),
            false,
            null,
            Set.of(),
            executor
        );

        ActionResult result = handler.executeAction(Map.of(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("ACTION_EXECUTION_FAILED");
        assertThat(result.getMessage()).contains("access mode");
    }

    @Test
    void shouldEscapeConfirmationFallbackAndResolvedValue() {
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            AIActionMetaData.builder()
                .name("commerce_update_cart")
                .category("commerce")
                .accessMode(ActionAccessMode.WRITE_ONLY)
                .build(),
            true,
            "{{cart_update_confirmation|Add <item> to cart}}?",
            Set.of(),
            null
        );

        assertThat(handler.getConfirmationMessage(
            Map.of("cart_update_confirmation", "Add <Wax> to your cart"),
            null
        )).isEqualTo("Add &lt;Wax&gt; to your cart?");
        assertThat(handler.getConfirmationMessage(Map.of(), null)).isEqualTo("Add &lt;item&gt; to cart?");
    }

    @Test
    void shouldBuildCompactLlmFactsFromConfiguredProjection() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionPayload.object(Map.of(
                "success", true,
                "data", Map.of(
                    "query", "Find candidates",
                    "documents", List.of(
                        candidateRecord("Beta Candidate", "11.5", true),
                        candidateRecord("Alpha Candidate", "8.5", true),
                        candidateRecord("Gamma Candidate", "7.0", false),
                        candidateRecord("Delta Candidate", "6.5", true)
                    ),
                    "totalResults", 4,
                    "returnedResults", 4
                )
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null,
            configuredFacts()
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, actionContext(Map.of(
            "maxScore", 10,
            "readyOnly", true
        )));

        assertThat(facts).isPresent();
        assertThat(facts.get())
            .containsEntry("action", "search_records")
            .containsEntry("query", "Find candidates")
            .containsEntry("totalResults", 4)
            .containsEntry("returnedResults", 4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) facts.get().get("records");
        assertThat(records)
            .extracting(record -> record.get("name"))
            .containsExactly("Delta Candidate", "Alpha Candidate", "Gamma Candidate", "Beta Candidate");
        assertThat(records.getFirst())
            .containsEntry("scoreValue", "6.5")
            .containsEntry("isReady", true)
            .doesNotContainKey("internalPayload");
    }

    @Test
    void shouldEmitConfiguredConstraintMatchesAndNumericSummaryWithoutDomainRules() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionPayload.object(Map.of(
                "data", Map.of(
                    "query", "Find ready candidates with score <= 10",
                    "documents", List.of(
                        candidateRecord("Beta Candidate", "11.5", true),
                        candidateRecord("Alpha Candidate", "8.5", true),
                        candidateRecord("Gamma Candidate", "7.0", false),
                        candidateRecord("Delta Candidate", "6.5", true)
                    ),
                    "totalResults", 4,
                    "returnedResults", 4
                )
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null,
            configuredFacts()
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, actionContext(Map.of(
            "maxScore", 10,
            "readyOnly", true
        )));

        assertThat(facts).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> constraintMatches = (List<Map<String, Object>>) facts.get().get("recordsConstraintMatches");
        assertThat(constraintMatches)
            .extracting(record -> record.get("name"))
            .containsExactly("Delta Candidate", "Alpha Candidate");
        assertThat(facts.get()).containsEntry("recordsConstraintMatchCount", 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> allSummary = (Map<String, Object>) facts.get().get("recordsScoreSummary");
        assertThat(allSummary)
            .containsEntry("scoredRecords", 4)
            .containsEntry("lowestScoreName", "Delta Candidate")
            .containsEntry("lowestScore", 6.5)
            .containsEntry("highestScoreName", "Beta Candidate")
            .containsEntry("highestScore", 11.5);

        @SuppressWarnings("unchecked")
        Map<String, Object> matchedSummary = (Map<String, Object>) facts.get().get("recordsMatchedScoreSummary");
        assertThat(matchedSummary)
            .containsEntry("scoredRecords", 2)
            .containsEntry("lowestScoreName", "Delta Candidate")
            .containsEntry("lowestScore", 6.5)
            .containsEntry("highestScoreName", "Alpha Candidate")
            .containsEntry("highestScore", 8.5)
            .containsEntry("highestReady", true);
    }

    @Test
    void shouldNotInferConfiguredConstraintsFromOriginalRequestText() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionPayload.object(Map.of(
                "data", Map.of(
                    "query", "ready candidates",
                    "documents", List.of(
                        candidateRecord("Beta Candidate", "11.5", true),
                        candidateRecord("Alpha Candidate", "8.5", true),
                        candidateRecord("Gamma Candidate", "7.0", false),
                        candidateRecord("Delta Candidate", "6.5", true)
                    ),
                    "totalResults", 4,
                    "returnedResults", 4
                )
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null,
            configuredFacts()
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-1");
        ActionContext context = new ActionContext(
            orchestrationContext,
            PipelineContext.from("Find ready candidates under 10", orchestrationContext)
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, context);

        assertThat(facts).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> constraintMatches = (List<Map<String, Object>>) facts.get().get("recordsConstraintMatches");
        assertThat(constraintMatches).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) facts.get().get("records");
        assertThat(records)
            .extracting(record -> record.get("name"))
            .containsExactly("Beta Candidate", "Alpha Candidate", "Gamma Candidate", "Delta Candidate");
        assertThat(facts.get()).doesNotContainKey("recordsConstraintMatchCount");
    }

    @Test
    void shouldUseGenericFallbackProjectionWhenNoCatalogProjectionIsConfigured() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionPayload.object(Map.of(
                "data", Map.of(
                    "query", "alpha",
                    "documents", List.of(Map.of(
                        "id", "record-1",
                        "name", "Alpha Record",
                        "metadata", Map.of(
                            "status", "ready",
                            "rank", 3,
                            "internalPayload", "bounded scalar evidence"
                        ),
                        "nested", Map.of("ignoredNestedList", List.of("a", "b"))
                    )),
                    "totalResults", 1
                )
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, null);

        assertThat(facts).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) facts.get().get("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst())
            .containsEntry("name", "Alpha Record")
            .containsEntry("status", "ready")
            .containsEntry("rank", 3)
            .doesNotContainKey("ignoredNestedList");
    }

    @Test
    void shouldUseGenericFallbackProjectionForSingleReadObjectWhenNoCatalogProjectionIsConfigured() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Product details")
            .data(ActionPayload.object(Map.of(
                "sku", "SKU-0001",
                "name", "Premium Wireless Headphones",
                "price", 50.99,
                "currency", "USD",
                "inStockQty", 6,
                "metadata", Map.of(
                    "category", "Travel",
                    "internalList", List.of("ignored")
                )
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, null);

        assertThat(facts).isPresent();
        assertThat(facts.get()).containsEntry("recordCount", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) facts.get().get("record");
        assertThat(record)
            .containsEntry("sku", "SKU-0001")
            .containsEntry("name", "Premium Wireless Headphones")
            .containsEntry("price", 50.99)
            .containsEntry("currency", "USD")
            .containsEntry("inStockQty", 6)
            .containsEntry("category", "Travel")
            .doesNotContainKey("internalList");
    }

    @Test
    void shouldNotUseGenericFallbackProjectionForSingleWriteObjectWhenNoCatalogProjectionIsConfigured() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Cart updated")
            .data(ActionPayload.object(Map.of(
                "cartId", "cart-1",
                "checkoutUrl", "https://checkout.example"
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            AIActionMetaData.builder()
                .name("update_cart")
                .category("generic")
                .accessMode(ActionAccessMode.WRITE_ONLY)
                .build(),
            false,
            null,
            Set.of(),
            null
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, null);

        assertThat(facts).isPresent();
        assertThat(facts.get()).doesNotContainKeys("record", "recordCount");
    }

    @Test
    void shouldExposeMcpToolTextJsonAsFallbackDocuments() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("MCP tool result")
            .data(ActionPayload.object(Map.of(
                "adapterType", "mcp-tool",
                "mcpToolName", "search_shop_policies_and_faqs",
                "evidenceType", "MCP_TOOL_RESULT",
                "toolResult", Map.of(
                    "content", List.of(Map.of(
                        "type", "text",
                        "text", """
                            [
                              {
                                "question": "Do you allow customers to request and manage their own returns?",
                                "answer": "Customers must contact the merchant to request a return."
                              },
                              {
                                "question": "Do you accept store credit?",
                                "answer": "The store accepts store credit as a payment method."
                              }
                            ]
                            """
                    )),
                    "isError", false
                )
            )))
            .build();
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, null);

        assertThat(facts).isPresent();
        assertThat(facts.get()).containsEntry("documentsCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) facts.get().get("documents");
        assertThat(documents.getFirst())
            .containsEntry("question", "Do you allow customers to request and manage their own returns?")
            .containsEntry("answer", "Customers must contact the merchant to request a return.");
    }

    @Test
    void shouldBuildConfiguredFactsFromJsonTextRootAndIndexedListPaths() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("MCP tool result")
            .data(ActionPayload.object(Map.of(
                "toolResult", Map.of(
                    "content", List.of(Map.of(
                        "type", "text",
                        "text", """
                            {
                              "products": [
                                {
                                  "id": "commerce://resource/Product/7930570047571",
                                  "title": "Selling Plans Ski Wax",
                                  "variants": [
                                    {
                                      "id": "commerce://resource/ProductVariant/44506675314771",
                                      "title": "Selling Plans Ski Wax",
                                      "availability": {"available": true}
                                    }
                                  ]
                                }
                              ]
                            }
                            """
                    )),
                    "isError", false
                )
            )))
            .build();
        ConnectorActionLlmFactsListDefinition variants = new ConnectorActionLlmFactsListDefinition(
            "products.0.variants",
            "documents",
            3,
            List.of("id", "title", "availability.available"),
            null,
            300,
            List.of(),
            null,
            List.of()
        );
        ConnectorAIActionHandler handler = new ConnectorAIActionHandler(
            metadata(),
            false,
            null,
            Set.of(),
            null,
            new ConnectorActionLlmFactsDefinition(
                "toolResult.content.0.text",
                List.of(),
                List.of(variants),
                List.of()
            )
        );

        Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(actionResult, null);

        assertThat(facts).isPresent();
        assertThat(facts.get()).containsEntry("documentsCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) facts.get().get("documents");
        assertThat(documents).singleElement().satisfies(document -> assertThat(document)
            .containsEntry("id", "commerce://resource/ProductVariant/44506675314771")
            .containsEntry("title", "Selling Plans Ski Wax")
            .containsEntry("available", true));
    }

    private AIActionMetaData metadata() {
        return AIActionMetaData.builder()
            .name("search_records")
            .category("generic")
            .accessMode(ActionAccessMode.READ)
            .build();
    }

    private Map<String, Object> candidateRecord(String name, String score, boolean ready) {
        return Map.of(
            "name", name,
            "kind", "candidate",
            "metadata", Map.of(
                "scoreValue", score,
                "isReady", ready,
                "internalPayload", "large internal payload"
            )
        );
    }

    private ActionContext actionContext(Map<String, Object> params) {
        OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-1");
        return new ActionContext(orchestrationContext, PipelineContext.from("Find candidates", orchestrationContext), params);
    }

    private ConnectorActionLlmFactsDefinition configuredFacts() {
        ConnectorActionLlmFactsRuleDefinition numericUpperBound = new ConnectorActionLlmFactsRuleDefinition(
            "PARAM_NUMERIC_UPPER_BOUND",
            "scoreValue",
            "maxScore",
            "<=",
            null,
            0,
            300,
            -300,
            -300,
            true
        );
        ConnectorActionLlmFactsRuleDefinition readyRule = new ConnectorActionLlmFactsRuleDefinition(
            "PARAM_BOOLEAN_TRUE",
            "isReady",
            "readyOnly",
            null,
            null,
            0,
            150,
            0,
            -150,
            false
        );
        ConnectorActionLlmFactsConstraintDefinition constraints = new ConnectorActionLlmFactsConstraintDefinition(
            "recordsConstraintMatches",
            "recordsConstraintMatchCount",
            List.of("name", "scoreValue", "isReady"),
            List.of(numericUpperBound, readyRule)
        );
        ConnectorActionLlmFactsSummaryExtraFieldDefinition readyExtra = new ConnectorActionLlmFactsSummaryExtraFieldDefinition(
            "isReady",
            "lowestReady",
            "highestReady"
        );
        ConnectorActionLlmFactsSummaryDefinition allSummary = new ConnectorActionLlmFactsSummaryDefinition(
            "recordsScoreSummary",
            "ALL",
            "scoreValue",
            "scoredRecords",
            "lowestScore",
            "highestScore",
            "name",
            "lowestScoreName",
            "highestScoreName",
            List.of(readyExtra)
        );
        ConnectorActionLlmFactsSummaryDefinition matchedSummary = new ConnectorActionLlmFactsSummaryDefinition(
            "recordsMatchedScoreSummary",
            "CONSTRAINT_MATCHES",
            "scoreValue",
            "scoredRecords",
            "lowestScore",
            "highestScore",
            "name",
            "lowestScoreName",
            "highestScoreName",
            List.of(readyExtra)
        );
        ConnectorActionLlmFactsListDefinition listDefinition = new ConnectorActionLlmFactsListDefinition(
            "documents",
            "records",
            5,
            List.of("name", "kind", "metadata.scoreValue", "metadata.isReady"),
            null,
            300,
            List.of(numericUpperBound, readyRule),
            constraints,
            List.of(allSummary, matchedSummary)
        );
        return new ConnectorActionLlmFactsDefinition(
            "data",
            List.of("query", "totalResults", "returnedResults"),
            List.of(listDefinition),
            List.of()
        );
    }
}
