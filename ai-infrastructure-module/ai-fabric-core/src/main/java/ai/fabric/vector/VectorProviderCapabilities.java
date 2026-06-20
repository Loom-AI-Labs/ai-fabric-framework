package ai.fabric.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured capability evidence for an AI Fabric vector provider.
 *
 * <p>The flat {@code adminDiagnostics()} keys remain for compatibility, but this descriptor is the
 * typed source that docs, readiness checks, and provider contract tests can share.</p>
 */
public record VectorProviderCapabilities(
    String providerName,
    String providerClass,
    String nativeClient,
    boolean vectorScan,
    boolean searchMetadataFiltering,
    boolean scanMetadataFiltering,
    boolean exactFetchById,
    boolean clearByEntityType,
    boolean efficientEntityTypeCount,
    boolean hybridSearch,
    boolean keywordSearch,
    String searchFilterMode,
    String scanFilterMode,
    String metadataFilterSubset,
    String entityTypeCountMode,
    String entityTypeClearMode,
    String consistencyModel,
    boolean durableStorage,
    boolean productionProfileSafe
) {

    public VectorProviderCapabilities {
        providerName = optionalText(providerName);
        providerClass = optionalText(providerClass);
        nativeClient = optionalText(nativeClient);
        searchFilterMode = optionalText(searchFilterMode);
        scanFilterMode = optionalText(scanFilterMode);
        metadataFilterSubset = optionalText(metadataFilterSubset);
        entityTypeCountMode = optionalText(entityTypeCountMode);
        entityTypeClearMode = optionalText(entityTypeClearMode);
        consistencyModel = optionalText(consistencyModel);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean lifecycleAdminCompatible() {
        return vectorScan
            && searchMetadataFiltering
            && scanMetadataFiltering
            && exactFetchById
            && clearByEntityType
            && efficientEntityTypeCount;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("providerName", providerName);
        map.put("providerClass", providerClass);
        map.put("nativeClient", nativeClient);
        map.put("supportsVectorScan", vectorScan);
        map.put("supportsSearchMetadataFiltering", searchMetadataFiltering);
        map.put("supportsScanMetadataFiltering", scanMetadataFiltering);
        map.put("supportsExactFetchById", exactFetchById);
        map.put("supportsClearByEntityType", clearByEntityType);
        map.put("supportsEfficientEntityTypeCount", efficientEntityTypeCount);
        map.put("supportsHybridSearch", hybridSearch);
        map.put("supportsKeywordSearch", keywordSearch);
        map.put("searchFilterMode", searchFilterMode);
        map.put("scanFilterMode", scanFilterMode);
        map.put("metadataFilterSubset", metadataFilterSubset);
        map.put("entityTypeCountMode", entityTypeCountMode);
        map.put("entityTypeClearMode", entityTypeClearMode);
        map.put("consistencyModel", consistencyModel);
        map.put("durableStorage", durableStorage);
        map.put("productionProfileSafe", productionProfileSafe);
        map.put("lifecycleAdminCompatible", lifecycleAdminCompatible());
        return Collections.unmodifiableMap(map);
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Builder {
        private String providerName;
        private String providerClass;
        private String nativeClient;
        private boolean vectorScan;
        private boolean searchMetadataFiltering;
        private boolean scanMetadataFiltering;
        private boolean exactFetchById;
        private boolean clearByEntityType;
        private boolean efficientEntityTypeCount;
        private boolean hybridSearch;
        private boolean keywordSearch;
        private String searchFilterMode;
        private String scanFilterMode;
        private String metadataFilterSubset;
        private String entityTypeCountMode;
        private String entityTypeClearMode;
        private String consistencyModel;
        private boolean durableStorage = true;
        private boolean productionProfileSafe = true;

        private Builder() {
        }

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public Builder providerClass(String providerClass) {
            this.providerClass = providerClass;
            return this;
        }

        public Builder nativeClient(String nativeClient) {
            this.nativeClient = nativeClient;
            return this;
        }

        public Builder vectorScan(boolean vectorScan) {
            this.vectorScan = vectorScan;
            return this;
        }

        public Builder searchMetadataFiltering(boolean searchMetadataFiltering) {
            this.searchMetadataFiltering = searchMetadataFiltering;
            return this;
        }

        public Builder scanMetadataFiltering(boolean scanMetadataFiltering) {
            this.scanMetadataFiltering = scanMetadataFiltering;
            return this;
        }

        public Builder exactFetchById(boolean exactFetchById) {
            this.exactFetchById = exactFetchById;
            return this;
        }

        public Builder clearByEntityType(boolean clearByEntityType) {
            this.clearByEntityType = clearByEntityType;
            return this;
        }

        public Builder efficientEntityTypeCount(boolean efficientEntityTypeCount) {
            this.efficientEntityTypeCount = efficientEntityTypeCount;
            return this;
        }

        public Builder hybridSearch(boolean hybridSearch) {
            this.hybridSearch = hybridSearch;
            return this;
        }

        public Builder keywordSearch(boolean keywordSearch) {
            this.keywordSearch = keywordSearch;
            return this;
        }

        public Builder searchFilterMode(String searchFilterMode) {
            this.searchFilterMode = searchFilterMode;
            return this;
        }

        public Builder scanFilterMode(String scanFilterMode) {
            this.scanFilterMode = scanFilterMode;
            return this;
        }

        public Builder metadataFilterSubset(String metadataFilterSubset) {
            this.metadataFilterSubset = metadataFilterSubset;
            return this;
        }

        public Builder entityTypeCountMode(String entityTypeCountMode) {
            this.entityTypeCountMode = entityTypeCountMode;
            return this;
        }

        public Builder entityTypeClearMode(String entityTypeClearMode) {
            this.entityTypeClearMode = entityTypeClearMode;
            return this;
        }

        public Builder consistencyModel(String consistencyModel) {
            this.consistencyModel = consistencyModel;
            return this;
        }

        public Builder durableStorage(boolean durableStorage) {
            this.durableStorage = durableStorage;
            return this;
        }

        public Builder productionProfileSafe(boolean productionProfileSafe) {
            this.productionProfileSafe = productionProfileSafe;
            return this;
        }

        public VectorProviderCapabilities build() {
            return new VectorProviderCapabilities(
                providerName,
                providerClass,
                nativeClient,
                vectorScan,
                searchMetadataFiltering,
                scanMetadataFiltering,
                exactFetchById,
                clearByEntityType,
                efficientEntityTypeCount,
                hybridSearch,
                keywordSearch,
                searchFilterMode,
                scanFilterMode,
                metadataFilterSubset,
                entityTypeCountMode,
                entityTypeClearMode,
                consistencyModel,
                durableStorage,
                productionProfileSafe
            );
        }
    }
}
