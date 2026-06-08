package com.ai.infrastructure.rag.source;

import org.springframework.util.StringUtils;

import java.util.Arrays;

public enum KnowledgeSourceAdapterType {

    DEPLOYMENT_PRIVATE_VECTOR("deployment-private-vector"),
    SHARED_INDEX("shared-index"),
    PROVIDER_MANAGED_DATASET("provider-managed-dataset"),
    REMOTE_SEARCH_API("remote-search-api");

    private final String wireValue;

    KnowledgeSourceAdapterType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static KnowledgeSourceAdapterType fromWireValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Arrays.stream(values())
            .filter(candidate -> candidate.wireValue.equalsIgnoreCase(value.trim()))
            .findFirst()
            .orElse(null);
    }
}
