package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.Intent;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagContextSupportTest {

    @Test
    void shouldBuildTaggedContextFromDocuments() {
        String context = RagContextSupport.buildContextFromDocuments(List.of(
            doc("doc-1", "Snowboard", "A stable all-mountain board.", Map.of("vectorSpace", "product"), 0.8, null)
        ));

        assertThat(context).contains("[vectorSpace=product id=doc-1]");
        assertThat(context).contains("Snowboard");
        assertThat(context).contains("A stable all-mountain board.");
        assertThat(RagContextSupport.buildContextFromDocuments(List.of()))
            .isEqualTo(RagContextSupport.NO_CONTEXT_MESSAGE);
    }

    @Test
    void shouldApplyGenerationContextBudgetsToDocumentsAndFallbackContext() {
        OrchestrationPolicy.RagBudgets budgets = new OrchestrationPolicy.RagBudgets(
            null,
            null,
            null,
            null,
            1,
            48,
            List.of(),
            null
        );

        String documentContext = RagContextSupport.buildGenerationContext(
            List.of(
                doc("doc-1", "First", "First content", Map.of(), 0.7, null),
                doc("doc-2", "Second", "Second content", Map.of(), 0.6, null)
            ),
            "fallback context",
            budgets
        );

        assertThat(documentContext).contains("First");
        assertThat(documentContext).doesNotContain("Second");
        assertThat(documentContext.length()).isLessThanOrEqualTo(48);

        String fallback = RagContextSupport.buildGenerationContext(List.of(), "abcdef", budgets);
        assertThat(fallback).isEqualTo("abcdef");

        OrchestrationPolicy.RagBudgets smallFallbackBudget = new OrchestrationPolicy.RagBudgets(
            null,
            null,
            null,
            null,
            null,
            3,
            List.of(),
            null
        );
        assertThat(RagContextSupport.buildGenerationContext(List.of(), "abcdef", smallFallbackBudget))
            .isEqualTo("abc");
    }

    @Test
    void shouldPrependPinnedTargetsContextAndResolveHeadersBySource() {
        PipelineContext context = PipelineContext.from("query", OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(target("p1", "product", ResolvedTargetSource.WORKING_SET)))
            .pinnedTargetsContext("PINNED TARGETS:\n- existing target")
            .build();

        assertThat(RagContextSupport.prependPinnedTargetsContext("retrieved context", context))
            .isEqualTo("PINNED TARGETS:\n- existing target\n\nretrieved context");

        assertThat(RagContextSupport.resolvePinnedTargetsHeader(
            List.of(target("attachment-1", "document", ResolvedTargetSource.REQUEST_ATTACHMENTS))
        )).isEqualTo("ATTACHMENTS (user-provided text evidence visible to the assistant; authoritative for this turn):");

        assertThat(RagContextSupport.resolvePinnedTargetsHeader(
            List.of(target("stored-1", "product", ResolvedTargetSource.SESSION_METADATA))
        )).isEqualTo("PINNED TARGETS (previously pinned; not current UI selection):");
    }

    @Test
    void shouldSkipRetrievalOnlyWhenPinnedTargetsCoverRequestedSpaces() {
        PipelineContext context = PipelineContext.from("query", OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(
                target("p1", "product", ResolvedTargetSource.WORKING_SET),
                target("policy-1", "policy", ResolvedTargetSource.WORKING_SET)
            ))
            .build();

        Intent covered = Intent.builder()
            .requiresTargetResolution(true)
            .vectorSpace("Product, policy")
            .build();
        Intent missing = Intent.builder()
            .requiresTargetResolution(true)
            .vectorSpace("product, orders")
            .build();
        Intent noVectorSpace = Intent.builder()
            .requiresTargetResolution(true)
            .build();

        assertThat(RagContextSupport.shouldSkipRetrievalForPinnedTargets(covered, context)).isTrue();
        assertThat(RagContextSupport.shouldSkipRetrievalForPinnedTargets(missing, context)).isFalse();
        assertThat(RagContextSupport.shouldSkipRetrievalForPinnedTargets(noVectorSpace, context)).isFalse();
    }

    @Test
    void shouldParseAndValidateRequestedVectorSpaces() {
        assertThat(RagContextSupport.parseVectorSpaces(" product, policy,product ,, "))
            .containsExactly("product", "policy");

        AIEntityConfigurationLoader loader = mock(AIEntityConfigurationLoader.class);
        when(loader.getSupportedEntityTypes()).thenReturn(Set.of("product", "policy"));

        RagContextSupport.VectorSpaceValidation validation =
            RagContextSupport.validateRequestedVectorSpaces(List.of(" Product ", "orders", "product"), loader);

        assertThat(validation.valid()).containsExactly("product");
        assertThat(validation.invalid()).containsExactly("orders");
        assertThat(validation.normalizedOrFiltered()).isTrue();
        assertThat(validation.hasInvalid()).isTrue();

        RagContextSupport.VectorSpaceValidation noConfiguredSpaces =
            RagContextSupport.validateRequestedVectorSpaces(List.of(" Product ", "product"), null);
        assertThat(noConfiguredSpaces.valid()).containsExactly("product");
        assertThat(noConfiguredSpaces.invalid()).isEmpty();
        assertThat(noConfiguredSpaces.normalizedOrFiltered()).isTrue();
    }

    @Test
    void shouldTagDocumentsAndResolveBestScore() {
        RAGResponse.RAGDocument original = doc(
            "doc-1",
            "Title",
            "Content",
            Map.of("source", "catalog"),
            null,
            0.42
        );

        RAGResponse.RAGDocument tagged = RagContextSupport.tagDocumentWithVectorSpace(original, "product");

        assertThat(tagged).isNotSameAs(original);
        assertThat(tagged.getMetadata()).containsEntry("source", "catalog");
        assertThat(tagged.getMetadata()).containsEntry("vectorSpace", "product");
        assertThat(RagContextSupport.bestDocumentScore(List.of(
            original,
            doc("doc-2", "Other", "Other", Map.of(), 0.91, null)
        ))).isEqualTo(0.91);
    }

    private RAGResponse.RAGDocument doc(String id,
                                        String title,
                                        String content,
                                        Map<String, Object> metadata,
                                        Double score,
                                        Double similarity) {
        return RAGResponse.RAGDocument.builder()
            .id(id)
            .title(title)
            .content(content)
            .metadata(metadata)
            .score(score)
            .similarity(similarity)
            .build();
    }

    private ResolvedTarget target(String id, String vectorSpace, ResolvedTargetSource source) {
        return ResolvedTarget.builder()
            .id(id)
            .vectorSpace(vectorSpace)
            .contentText("Target " + id)
            .source(source)
            .build();
    }
}
