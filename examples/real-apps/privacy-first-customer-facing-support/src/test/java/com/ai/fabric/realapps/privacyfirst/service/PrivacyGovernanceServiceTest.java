package com.ai.fabric.realapps.privacyfirst.service;

import ai.fabric.deletion.UserDataDeletionResult;
import ai.fabric.deletion.UserDataDeletionService;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacyGovernanceServiceTest {

    private final SupportMessageService supportMessageService = mock(SupportMessageService.class);
    private final IndexCatalog indexCatalog = mock(IndexCatalog.class);
    private final UserDataDeletionService deletionService = mock(UserDataDeletionService.class);
    private final PrivacyGovernanceService service = new PrivacyGovernanceService(
        supportMessageService,
        availableProvider(indexCatalog),
        availableProvider(deletionService)
    );

    @Test
    void inventoryCombinesDomainAndIndexedEvidence() {
        when(supportMessageService.findByCustomerId("cust-1001")).thenReturn(List.of(new SupportMessage(), new SupportMessage()));
        when(indexCatalog.scan(any(IndexCatalogScanRequest.class))).thenReturn(IndexCatalogScanPage.builder()
            .entries(List.of(IndexCatalogEntry.builder()
                .entityType("support-message")
                .entityId("1")
                .metadata(Map.of("customerId", "cust-1001"))
                .build()))
            .build());

        PrivacyGovernanceService.CustomerPrivacyInventory inventory = service.inventory("cust-1001");

        assertThat(inventory.domainRecordCount()).isEqualTo(2);
        assertThat(inventory.indexedRecordCount()).isEqualTo(1);
        assertThat(inventory.indexedRecords().getFirst().getEntityId()).isEqualTo("1");
    }

    @Test
    void delegatesDeletionToAiFabricGovernanceService() {
        UserDataDeletionResult result = UserDataDeletionResult.builder()
            .userId("cust-1001")
            .status(UserDataDeletionResult.Status.COMPLETED)
            .timestamp(LocalDateTime.now())
            .build();
        when(deletionService.deleteUser("cust-1001")).thenReturn(result);

        assertThat(service.deleteCustomer("cust-1001")).isSameAs(result);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> availableProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
