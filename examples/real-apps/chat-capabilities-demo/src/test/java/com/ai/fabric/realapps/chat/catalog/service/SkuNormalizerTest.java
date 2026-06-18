package com.ai.fabric.realapps.chat.catalog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkuNormalizerTest {

    @Test
    void normalizesBlankSkuToEmptyString() {
        assertThat(SkuNormalizer.normalize(" ")).isEmpty();
        assertThat(SkuNormalizer.normalizeForLookup(null)).isEmpty();
    }

    @Test
    void normalizesWhitespaceDashesAndLookupCase() {
        assertThat(SkuNormalizer.normalize(" SKU\u20110001 ")).isEqualTo("SKU-0001");
        assertThat(SkuNormalizer.normalizeForLookup(" SKU\u20110001 ")).isEqualTo("sku-0001");
    }
}
