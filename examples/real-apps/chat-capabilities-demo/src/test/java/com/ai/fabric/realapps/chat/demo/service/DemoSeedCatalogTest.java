package com.ai.fabric.realapps.chat.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoSeedCatalogTest {

    @Test
    void policiesContainConcreteOpenedLaptopReturnRules() {
        assertEquals(20, DemoSeedCatalog.policies().size());

        String returnsPolicy = policyText("Returns and refund policy");
        String laptopPolicy = policyText("Laptop warranty policy");
        String openBoxPolicy = policyText("Open box policy");

        assertTrue(returnsPolicy.contains("opened gaming laptops"));
        assertTrue(returnsPolicy.contains("30 days"));
        assertTrue(openBoxPolicy.contains("opened gaming laptops"));
        assertTrue(openBoxPolicy.contains("no restocking fee"));
        assertTrue(laptopPolicy.contains("warranty covers manufacturing defects"));
        assertTrue(laptopPolicy.contains("separate from return eligibility"));

        assertFalse(DemoSeedCatalog.policies().stream()
            .map(DemoSeedCatalog.PolicySeed::text)
            .anyMatch(text -> text.contains("Policy guidance for")));
    }

    private String policyText(String title) {
        return DemoSeedCatalog.policies().stream()
            .filter(policy -> policy.title().equals(title))
            .findFirst()
            .orElseThrow()
            .text()
            .toLowerCase();
    }
}
