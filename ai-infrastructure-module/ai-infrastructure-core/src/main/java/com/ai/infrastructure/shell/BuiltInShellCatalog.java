package com.ai.infrastructure.shell;

import java.util.List;
import java.util.Set;

public final class BuiltInShellCatalog {

    public static final List<String> MODULE_IDS = List.of(
        "search",
        "ai-search",
        "product-catalog",
        "products",
        "cart",
        "orders",
        "purchase-orders",
        "policies",
        "docs",
        "reviews",
        "actions",
        "customer-account",
        "addresses",
        "support"
    );

    public static final List<String> CARD_IDS = List.of(
        "product-list",
        "product-detail",
        "cart-summary",
        "order-status",
        "purchase-order-status",
        "policy-summary",
        "review-summary",
        "support-ticket-status",
        "address-summary",
        "pricing-summary"
    );

    public static final List<String> EVIDENCE_BLOCK_IDS = List.of(
        "default-document",
        "product-evidence",
        "policy-evidence",
        "review-evidence"
    );

    private static final Set<String> MODULE_ID_SET = Set.copyOf(MODULE_IDS);
    private static final Set<String> CARD_ID_SET = Set.copyOf(CARD_IDS);
    private static final Set<String> EVIDENCE_BLOCK_ID_SET = Set.copyOf(EVIDENCE_BLOCK_IDS);

    private BuiltInShellCatalog() {
    }

    public static boolean supportsModuleId(String moduleId) {
        return moduleId != null && MODULE_ID_SET.contains(moduleId.trim());
    }

    public static boolean supportsCardId(String cardId) {
        return cardId != null && CARD_ID_SET.contains(cardId.trim());
    }

    public static boolean supportsEvidenceBlockId(String evidenceBlockId) {
        return evidenceBlockId != null && EVIDENCE_BLOCK_ID_SET.contains(evidenceBlockId.trim());
    }
}
