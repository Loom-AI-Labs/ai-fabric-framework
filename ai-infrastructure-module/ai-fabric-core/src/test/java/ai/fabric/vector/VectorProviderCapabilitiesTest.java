package ai.fabric.vector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorProviderCapabilitiesTest {

    @Test
    void reportsLifecycleAdminCompatibilityOnlyWhenAllRequiredCapabilitiesArePresent() {
        VectorProviderCapabilities capabilities = VectorProviderCapabilities.builder()
            .providerName("qdrant")
            .providerClass("example.Qdrant")
            .nativeClient("qdrant-java-grpc-sdk")
            .vectorScan(true)
            .searchMetadataFiltering(true)
            .scanMetadataFiltering(true)
            .exactFetchById(true)
            .clearByEntityType(true)
            .efficientEntityTypeCount(true)
            .searchFilterMode("qdrant-payload-filter")
            .scanFilterMode("qdrant-payload-filter")
            .metadataFilterSubset("portable-scalar-exact-match")
            .entityTypeCountMode("qdrant-count-api")
            .entityTypeClearMode("qdrant-delete-collection")
            .consistencyModel("provider-durable")
            .build();

        assertThat(capabilities.lifecycleAdminCompatible()).isTrue();
        assertThat(capabilities.toMap())
            .containsEntry("providerName", "qdrant")
            .containsEntry("nativeClient", "qdrant-java-grpc-sdk")
            .containsEntry("supportsVectorScan", true)
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("supportsExactFetchById", true)
            .containsEntry("supportsClearByEntityType", true)
            .containsEntry("supportsEfficientEntityTypeCount", true)
            .containsEntry("entityTypeClearMode", "qdrant-delete-collection")
            .containsEntry("lifecycleAdminCompatible", true);
    }

    @Test
    void exposesImmutableMapAndNormalizesNullTextToEmptyStrings() {
        VectorProviderCapabilities capabilities = VectorProviderCapabilities.builder()
            .providerName(null)
            .nativeClient(null)
            .build();

        assertThat(capabilities.providerName()).isEmpty();
        assertThat(capabilities.nativeClient()).isEmpty();
        assertThatThrownBy(() -> capabilities.toMap().put("providerName", "changed"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
