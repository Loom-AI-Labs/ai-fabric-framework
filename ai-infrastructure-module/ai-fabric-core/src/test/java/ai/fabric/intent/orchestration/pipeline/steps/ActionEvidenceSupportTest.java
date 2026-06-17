package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActionEvidenceSupportTest {

    @Test
    void shouldNormalizeTrustedEvidenceFromMetadata() {
        Map<String, Set<String>> trusted = new LinkedHashMap<>();

        ActionEvidenceSupport.addTrustedEvidenceValues(trusted, Map.of(
            " SKU ", " ELEC-PHONE-002 ",
            "blank", "   "
        ));

        assertThat(trusted).containsKey("sku");
        assertThat(trusted.get("sku")).containsExactly("elec-phone-002");
        assertThat(trusted).doesNotContainKey("blank");
    }

    @Test
    void shouldAddPendingConfirmationEvidenceFromScalarAndIterableValues() {
        Map<String, Set<String>> trusted = new LinkedHashMap<>();

        boolean added = ActionEvidenceSupport.addTrustedEvidenceValues(trusted, Map.of(
            "sku", List.of("ELEC-PHONE-002", " ELEC-PHONE-003 "),
            "cartId", "CART-123"
        ));

        assertThat(added).isTrue();
        assertThat(trusted.get("sku")).containsExactly("elec-phone-002", "elec-phone-003");
        assertThat(trusted.get("cartid")).containsExactly("cart-123");
    }

    @Test
    void shouldExtractEvidenceBoundKeysRecursivelyFromSchemas() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "items", AIActionParamSchema.builder()
                    .name("items")
                    .type(AIActionParamType.ARRAY)
                    .items(AIActionParamSchema.builder()
                        .name("item")
                        .type(AIActionParamType.OBJECT)
                        .properties(Map.of(
                            "productVariantId", AIActionParamSchema.builder()
                                .name("productVariantId")
                                .evidenceBound(true)
                                .evidenceKeys(List.of("variant_id", "sku"))
                                .build()
                        ))
                        .build())
                    .build(),
                "cartId", AIActionParamSchema.builder()
                    .name("cartId")
                    .evidenceBound(true)
                    .build()
            ))
            .build();

        assertThat(ActionEvidenceSupport.evidenceBoundKeys(meta))
            .containsExactlyInAnyOrder("variant_id", "sku", "cartid");
    }

    @Test
    void shouldValidateEvidenceBoundValuesAgainstTrustedEvidence() {
        AIActionParamSchema schema = AIActionParamSchema.builder()
            .name("productVariantId")
            .evidenceBound(true)
            .evidenceKeys(List.of("variant_id"))
            .build();
        Map<String, Set<String>> trusted = Map.of("variant_id", Set.of("commerce://resource/productvariant/1"));

        assertThat(ActionEvidenceSupport.isEvidenceBoundValueTrusted(
            " commerce://resource/ProductVariant/1 ",
            schema,
            trusted
        )).isTrue();
        assertThat(ActionEvidenceSupport.isEvidenceBoundValueTrusted(
            "commerce://resource/ProductVariant/2",
            schema,
            trusted
        )).isFalse();
    }

    @Test
    void shouldFreezeTrustedEvidenceValues() {
        Map<String, Set<String>> trusted = new LinkedHashMap<>();
        ActionEvidenceSupport.addTrustedEvidenceValue(trusted, "sku", "ELEC-PHONE-002");

        Map<String, Set<String>> frozen = ActionEvidenceSupport.freezeTrustedEvidenceValues(trusted);

        assertThat(frozen).containsEntry("sku", Set.of("elec-phone-002"));
        assertThat(frozen).isUnmodifiable();
    }

    @Test
    void shouldBuildEvidenceBundleFromUserHistoryPinnedTargetsAttachmentsAndPendingEvidence() {
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("attachment-1")
                .vectorSpace("catalog")
                .contentText("Pinned attachment text")
                .metadata(Map.of("AttachmentSku", "ATT-SKU-1"))
                .build()))
            .build();

        PipelineContext context = PipelineContext.from("Add the phone", orchestrationContext)
            .toBuilder()
            .historyMessages(List.of(
                AIChatMessage.user("previous user request"),
                AIChatMessage.assistant("assistant text ignored")
            ))
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("target-1")
                .vectorSpace("products")
                .contentText("Target product text")
                .metadata(Map.of("Product_Variant_Id", "commerce://resource/ProductVariant/1"))
                .build()))
            .metadata(Map.of(
                PendingAction.TRUSTED_EVIDENCE_METADATA_KEY,
                Map.of("Cart_Id", List.of("CART-123"))
            ))
            .build();

        ActionEvidenceSupport.EvidenceBundle evidence = ActionEvidenceSupport.buildEvidenceBundle(context);

        assertThat(evidence.userEvidenceLower()).contains("add the phone", "previous user request");
        assertThat(evidence.userEvidenceLower()).doesNotContain("assistant text ignored");
        assertThat(evidence.pinnedEvidenceLower()).contains(
            "attachment-1",
            "pinned attachment text",
            "target product text",
            "commerce://resource/productvariant/1"
        );
        assertThat(evidence.trustedValuesByKey())
            .containsEntry("attachmentsku", Set.of("att-sku-1"))
            .containsEntry("product_variant_id", Set.of("commerce://resource/productvariant/1"))
            .containsEntry("cart_id", Set.of("cart-123"));
        assertThat(evidence.sourcesUsed())
            .containsEntry("user", true)
            .containsEntry("history", true)
            .containsEntry("pinned", true)
            .containsEntry("pendingConfirmationEvidence", true);
    }

    @Test
    void shouldBuildPendingTrustedEvidenceFromEvidenceBundleAndResolvedParams() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "cartId", AIActionParamSchema.builder()
                    .name("cartId")
                    .evidenceBound(true)
                    .evidenceKeys(List.of("cart_id"))
                    .build(),
                "items", AIActionParamSchema.builder()
                    .name("items")
                    .type(AIActionParamType.ARRAY)
                    .items(AIActionParamSchema.builder()
                        .name("item")
                        .type(AIActionParamType.OBJECT)
                        .properties(Map.of(
                            "productVariantId", AIActionParamSchema.builder()
                                .name("productVariantId")
                                .evidenceBound(true)
                                .evidenceKeys(List.of("variant_id", "sku"))
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        ActionEvidenceSupport.EvidenceBundle evidence = new ActionEvidenceSupport.EvidenceBundle(
            "",
            "",
            Map.of(
                "variant_id", Set.of("gid://variant/1"),
                "irrelevant", Set.of("ignored")
            ),
            Map.of()
        );

        Map<String, List<String>> trusted = ActionEvidenceSupport.pendingTrustedEvidenceValues(
            evidence,
            meta,
            Map.of(
                "cartId", "CART-123",
                "items", List.of(Map.of("productVariantId", "gid://variant/2"))
            ),
            Set.of("cartId", "items")
        );

        assertThat(trusted)
            .containsEntry("cart_id", List.of("cart-123"))
            .containsEntry("sku", List.of("gid://variant/2"));
        assertThat(trusted.get("variant_id")).containsExactlyInAnyOrder("gid://variant/1", "gid://variant/2");
        assertThat(trusted).doesNotContainKey("irrelevant");
    }

    @Test
    void shouldMergeTrustedActionEvidenceIntoMetadataAndRemoveStaleValuesWhenEmpty() {
        Map<String, Object> merged = ActionEvidenceSupport.mergeTrustedActionEvidence(
            Map.of("existing", true),
            Map.of("cart_id", List.of("cart-123"))
        );

        assertThat(merged)
            .containsEntry("existing", true)
            .containsKey(PendingAction.TRUSTED_EVIDENCE_METADATA_KEY);
        Map<?, ?> trusted = (Map<?, ?>) merged.get(PendingAction.TRUSTED_EVIDENCE_METADATA_KEY);
        assertThat(trusted.get("cart_id")).isEqualTo(List.of("cart-123"));

        Map<String, Object> cleared = ActionEvidenceSupport.mergeTrustedActionEvidence(
            merged,
            Map.of()
        );

        assertThat(cleared)
            .containsEntry("existing", true)
            .doesNotContainKey(PendingAction.TRUSTED_EVIDENCE_METADATA_KEY);
    }
}
