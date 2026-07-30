package ai.fabric.intent.retrieval.connector;

import ai.fabric.dto.RAGResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRetrievalDocumentSanitizerTest {

    @Test
    void assignsRequestedVectorSpaceAndDropsUnknownMetadataByDefault() {
        DefaultRetrievalDocumentSanitizer sanitizer = sanitizer(
            new AIRetrievalConnectorProperties()
        );

        RAGResponse.RAGDocument approved = sanitizer.sanitize(
            document(
                null,
                "https://docs.example/policy",
                Map.of("locale", "en_GB")
            ),
            context("policy")
        );

        assertThat(approved.getType()).isEqualTo("policy");
        assertThat(approved.getUrl())
            .isEqualTo("https://docs.example/policy");
        assertThat(approved.getMetadata()).isEmpty();
    }

    @Test
    void rejectsConflictingVectorSpaceFromFieldOrMetadata() {
        DefaultRetrievalDocumentSanitizer sanitizer = sanitizer(
            new AIRetrievalConnectorProperties()
        );

        assertThatThrownBy(() -> sanitizer.sanitize(
            document("private-policy", null, Map.of()),
            context("public-policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException.VECTOR_SPACE_MISMATCH
            );

        assertThatThrownBy(() -> sanitizer.sanitize(
            document(
                "public-policy",
                null,
                Map.of("vectorSpace", "private-policy")
            ),
            context("public-policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException.VECTOR_SPACE_MISMATCH
            );
    }

    @Test
    void projectsOnlyAllowlistedNestedMetadata() {
        AIRetrievalConnectorProperties properties =
            new AIRetrievalConnectorProperties();
        properties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(Set.of("locale", "citation.section"))
        );
        DefaultRetrievalDocumentSanitizer sanitizer =
            sanitizer(properties);

        RAGResponse.RAGDocument approved = sanitizer.sanitize(
            document(
                "policy",
                null,
                Map.of(
                    "locale",
                    "en_GB",
                    "citation",
                    Map.of("section", "returns", "internal", "secret"),
                    "tenantId",
                    "tenant-private"
                )
            ),
            context("policy")
        );

        assertThat(approved.getMetadata()).containsEntry(
            "locale",
            "en_GB"
        );
        assertThat(approved.getMetadata().get("citation"))
            .isEqualTo(Map.of("section", "returns"));
        assertThat(approved.getMetadata()).doesNotContainKey("tenantId");
    }

    @Test
    void rejectModeFailsOnUnknownOrReservedMetadata() {
        AIRetrievalConnectorProperties properties =
            new AIRetrievalConnectorProperties();
        properties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(Set.of("locale"))
        );
        properties.getResponsePolicy().setUnknownMetadataPolicy(
            RetrievalUnknownMetadataPolicy.REJECT
        );
        DefaultRetrievalDocumentSanitizer sanitizer =
            sanitizer(properties);

        assertThatThrownBy(() -> sanitizer.sanitize(
            document("policy", null, Map.of("tenantId", "tenant-1")),
            context("policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException
                    .METADATA_POLICY_VIOLATION
            );

        assertThatThrownBy(() -> sanitizer.sanitize(
            document(
                "policy",
                null,
                Map.of("_aifabricTrusted", true)
            ),
            context("policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException
                    .METADATA_POLICY_VIOLATION
            );
    }

    @Test
    void enforcesUrlSchemeAndOptionalHostSuffix() {
        AIRetrievalConnectorProperties properties =
            new AIRetrievalConnectorProperties();
        properties.getResponsePolicy().setAllowedUrlHostSuffixes(
            new LinkedHashSet<>(Set.of("docs.example"))
        );
        DefaultRetrievalDocumentSanitizer sanitizer =
            sanitizer(properties);

        RAGResponse.RAGDocument approved = sanitizer.sanitize(
            document(
                "policy",
                "https://help.docs.example/returns",
                Map.of()
            ),
            context("policy")
        );

        assertThat(approved.getUrl())
            .isEqualTo("https://help.docs.example/returns");
        assertThatThrownBy(() -> sanitizer.sanitize(
            document(
                "policy",
                "http://help.docs.example/returns",
                Map.of()
            ),
            context("policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException.URL_POLICY_VIOLATION
            );
        assertThatThrownBy(() -> sanitizer.sanitize(
            document(
                "policy",
                "https://docs.attacker.example/returns",
                Map.of()
            ),
            context("policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class);
    }

    @Test
    void canonicalizesConfiguredCitationHostSuffixes() {
        AIRetrievalConnectorProperties properties =
            new AIRetrievalConnectorProperties();
        properties.getResponsePolicy().setAllowedUrlHostSuffixes(
            new LinkedHashSet<>(Set.of(".DOCS.EXAMPLE."))
        );
        DefaultRetrievalDocumentSanitizer sanitizer =
            sanitizer(properties);

        RAGResponse.RAGDocument approved = sanitizer.sanitize(
            document(
                "policy",
                "https://help.docs.example./returns",
                Map.of()
            ),
            context("policy")
        );

        assertThat(approved.getUrl())
            .isEqualTo("https://help.docs.example./returns");
    }

    @Test
    void rejectsInvalidConfiguredCitationHostSuffix() {
        AIRetrievalConnectorProperties properties =
            new AIRetrievalConnectorProperties();
        properties.getResponsePolicy().setAllowedUrlHostSuffixes(
            new LinkedHashSet<>(Set.of("docs_example"))
        );

        assertThatThrownBy(() ->
            RetrievalResponsePolicy.from(properties)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid host suffix");
    }

    @Test
    void enforcesFiniteScoreAndMetadataBudgets() {
        AIRetrievalConnectorProperties properties =
            new AIRetrievalConnectorProperties();
        properties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(Set.of("tags"))
        );
        properties.getResponsePolicy().setMaxMetadataEntries(2);
        DefaultRetrievalDocumentSanitizer sanitizer =
            sanitizer(properties);

        RAGResponse.RAGDocument infinite = document(
            "policy",
            null,
            Map.of()
        );
        infinite.setScore(Double.POSITIVE_INFINITY);
        assertThatThrownBy(() ->
            sanitizer.sanitize(infinite, context("policy"))
        )
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException
                    .DOCUMENT_POLICY_VIOLATION
            );

        assertThatThrownBy(() -> sanitizer.sanitize(
            document(
                "policy",
                null,
                Map.of("tags", List.of("one", "two"))
            ),
            context("policy")
        ))
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(
                RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
            );
    }

    @Test
    void enforcesEveryExternalDocumentFieldBound() {
        AIRetrievalConnectorProperties idProperties =
            new AIRetrievalConnectorProperties();
        idProperties.getResponsePolicy()
            .setMaxDocumentIdCharacters(4);
        assertRejected(
            idProperties,
            document("policy", null, Map.of()),
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );

        AIRetrievalConnectorProperties contentProperties =
            new AIRetrievalConnectorProperties();
        contentProperties.getResponsePolicy().setMaxContentCharacters(8);
        assertRejected(
            contentProperties,
            document("policy", null, Map.of()),
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );

        AIRetrievalConnectorProperties sourceProperties =
            new AIRetrievalConnectorProperties();
        sourceProperties.getResponsePolicy().setMaxSourceCharacters(5);
        assertRejected(
            sourceProperties,
            document("policy", null, Map.of()),
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );

        AIRetrievalConnectorProperties urlProperties =
            new AIRetrievalConnectorProperties();
        urlProperties.getResponsePolicy().setMaxUrlCharacters(12);
        assertRejected(
            urlProperties,
            document(
                "policy",
                "https://docs.example/policy",
                Map.of()
            ),
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );

        AIRetrievalConnectorProperties vectorProperties =
            new AIRetrievalConnectorProperties();
        vectorProperties.getResponsePolicy()
            .setMaxVectorSpaceCharacters(4);
        assertRejected(
            vectorProperties,
            document("policy", null, Map.of()),
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );
    }

    @Test
    void enforcesMetadataDepthAndSerializedSize() {
        AIRetrievalConnectorProperties depthProperties =
            new AIRetrievalConnectorProperties();
        depthProperties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(Set.of("a.b.c"))
        );
        depthProperties.getResponsePolicy().setMaxMetadataDepth(2);
        assertRejected(
            depthProperties,
            document(
                "policy",
                null,
                Map.of("a", Map.of("b", Map.of("c", "value")))
            ),
            RetrievalDocumentPolicyException.RESPONSE_LIMIT_EXCEEDED
        );

        AIRetrievalConnectorProperties sizeProperties =
            new AIRetrievalConnectorProperties();
        sizeProperties.getResponsePolicy().setAllowedMetadataKeys(
            new LinkedHashSet<>(Set.of("note"))
        );
        sizeProperties.getResponsePolicy().setMaxMetadataCharacters(8);
        assertRejected(
            sizeProperties,
            document(
                "policy",
                null,
                Map.of("note", "long-value")
            ),
            RetrievalDocumentPolicyException.METADATA_POLICY_VIOLATION
        );
    }

    private static DefaultRetrievalDocumentSanitizer sanitizer(
        AIRetrievalConnectorProperties properties
    ) {
        return new DefaultRetrievalDocumentSanitizer(
            RetrievalResponsePolicy.from(properties),
            new ObjectMapper()
        );
    }

    private static RetrievalDocumentSanitizationContext context(
        String vectorSpace
    ) {
        return new RetrievalDocumentSanitizationContext(
            vectorSpace,
            10,
            0
        );
    }

    private static void assertRejected(
        AIRetrievalConnectorProperties properties,
        RAGResponse.RAGDocument document,
        String errorCode
    ) {
        assertThatThrownBy(() ->
            sanitizer(properties).sanitize(document, context("policy"))
        )
            .isInstanceOf(RetrievalDocumentPolicyException.class)
            .extracting(ex ->
                ((RetrievalDocumentPolicyException) ex).errorCode()
            )
            .isEqualTo(errorCode);
    }

    private static RAGResponse.RAGDocument document(
        String vectorSpace,
        String url,
        Map<String, Object> metadata
    ) {
        return RAGResponse.RAGDocument.builder()
            .id("doc-1")
            .content("Approved policy evidence.")
            .type(vectorSpace)
            .score(0.91)
            .source("knowledge-base")
            .url(url)
            .metadata(metadata)
            .build();
    }
}
