package com.ai.fabric.realapps.chat.catalog.service;

import java.util.Locale;
import org.springframework.util.StringUtils;

final class SkuNormalizer {

    private SkuNormalizer() {
    }

    static String normalize(String sku) {
        if (!StringUtils.hasText(sku)) {
            return null;
        }

        String normalized = sku.trim();
        normalized = normalizeDashes(normalized);
        normalized = removeWhitespace(normalized);
        return normalized;
    }

    static String normalizeForLookup(String sku) {
        String normalized = normalize(sku);
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : null;
    }

    private static String removeWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalizeDashes(String value) {
        // Normalize common unicode dashes/minus to ASCII hyphen-minus.
        return value
            .replace('\u2010', '-') // hyphen
            .replace('\u2011', '-') // non-breaking hyphen
            .replace('\u2012', '-') // figure dash
            .replace('\u2013', '-') // en dash
            .replace('\u2014', '-') // em dash
            .replace('\u2212', '-') // minus sign
            .replace('\uFE58', '-') // small em dash
            .replace('\uFE63', '-') // small hyphen-minus
            .replace('\uFF0D', '-'); // fullwidth hyphen-minus
    }
}
