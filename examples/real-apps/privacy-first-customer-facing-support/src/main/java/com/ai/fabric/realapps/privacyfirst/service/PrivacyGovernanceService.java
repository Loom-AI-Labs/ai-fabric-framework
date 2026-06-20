package com.ai.fabric.realapps.privacyfirst.service;

import ai.fabric.deletion.UserDataDeletionResult;
import ai.fabric.deletion.UserDataDeletionService;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PrivacyGovernanceService {

    private final SupportMessageService supportMessageService;
    private final ObjectProvider<IndexCatalog> indexCatalogProvider;
    private final ObjectProvider<UserDataDeletionService> deletionServiceProvider;

    public CustomerPrivacyInventory inventory(String customerId) {
        IndexCatalog indexCatalog = requireIndexCatalog();
        IndexCatalogScanPage page = indexCatalog.scan(IndexCatalogScanRequest.builder()
            .entityType("support-message")
            .metadataEquals(Map.of("customerId", customerId))
            .limit(100)
            .build());
        List<SupportMessage> domainRecords = supportMessageService.findByCustomerId(customerId);
        List<IndexCatalogEntry> entries = page != null && page.getEntries() != null ? page.getEntries() : List.of();
        return new CustomerPrivacyInventory(customerId, domainRecords.size(), entries.size(), entries);
    }

    public UserDataDeletionResult deleteCustomer(String customerId) {
        UserDataDeletionService deletionService = deletionServiceProvider.getIfAvailable();
        if (deletionService == null) {
            throw new IllegalStateException("UserDataDeletionService is not available");
        }
        return deletionService.deleteUser(customerId);
    }

    public List<SupportMessage> searchSafeMessages(String query, int limit) {
        return supportMessageService.semanticSearch(query, limit);
    }

    private IndexCatalog requireIndexCatalog() {
        IndexCatalog indexCatalog = indexCatalogProvider.getIfAvailable();
        if (indexCatalog == null) {
            throw new IllegalStateException("IndexCatalog is not available");
        }
        return indexCatalog;
    }

    public record CustomerPrivacyInventory(
        String customerId,
        int domainRecordCount,
        int indexedRecordCount,
        List<IndexCatalogEntry> indexedRecords
    ) {}
}
