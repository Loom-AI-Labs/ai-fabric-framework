package com.subscription.hub.config;

import ai.fabric.spi.RAGProvider;
import com.subscription.hub.service.AccountResolutionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolverPolicyRagIndexerTest {

    private final AccountResolutionService accountResolutionService = mock(AccountResolutionService.class);
    private final ObjectProvider<RAGProvider> ragProviderProvider = mock(ObjectProvider.class);

    @Test
    void indexesResolverPoliciesIntoPolicyVectorSpace() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AccountResolutionService.ResolutionPolicy policy = new AccountResolutionService.ResolutionPolicy(
            "PAYMENT_METHOD_REQUIRED",
            "Verified payment method required",
            "Paid usage requires a verified payment method.",
            "update_payment_method",
            true
        );
        when(ragProviderProvider.getIfAvailable()).thenReturn(ragProvider);
        when(accountResolutionService.policies()).thenReturn(List.of(policy));

        new ResolverPolicyRagIndexer(accountResolutionService, ragProviderProvider).run();

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(ragProvider).indexContent(
            eq(ResolverPolicyRagIndexer.POLICY_VECTOR_SPACE),
            eq("PAYMENT_METHOD_REQUIRED"),
            content.capture(),
            metadata.capture()
        );
        assertThat(content.getValue())
            .contains("Verified payment method required")
            .contains("update_payment_method");
        assertThat(metadata.getValue())
            .containsEntry("code", "PAYMENT_METHOD_REQUIRED")
            .containsEntry("actionName", "update_payment_method")
            .containsEntry("confirmationRequired", true);
    }

    @Test
    void skipsIndexingWhenRagProviderIsUnavailable() {
        when(ragProviderProvider.getIfAvailable()).thenReturn(null);

        new ResolverPolicyRagIndexer(accountResolutionService, ragProviderProvider).run();

        verify(accountResolutionService, never()).policies();
        verify(ragProviderProvider).getIfAvailable();
    }
}
