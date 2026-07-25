package com.ai.fabric.realapps.livesync.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.domain.SyncProduct;
import com.ai.fabric.realapps.livesync.repository.SyncProductRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncProductService {

    private final SyncProductRepository repository;

    @Transactional
    @AIProcess(
        entityType = SyncProduct.ENTITY_TYPE,
        operation = AIProcessOperation.CREATE,
        indexingStrategy = IndexingStrategy.AUTO
    )
    public SyncProduct createProduct(
        String workspaceId,
        String recordKey,
        String title,
        String summary,
        String specification,
        String category,
        BigDecimal price,
        String status
    ) {
        SyncProduct product = new SyncProduct();
        product.setWorkspaceId(SyncEntitySupport.requireText(workspaceId, "workspaceId"));
        product.setRecordKey(SyncEntitySupport.requireRecordKey(recordKey));
        product.setId(SyncEntitySupport.entityId(workspaceId, recordKey));
        apply(product, title, summary, specification, category, price, status);
        product.setRevision(1);
        product.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(product);
    }

    @Transactional
    @AIProcess(
        entityType = SyncProduct.ENTITY_TYPE,
        operation = AIProcessOperation.UPDATE,
        indexingStrategy = IndexingStrategy.SYNC
    )
    public SyncProduct updateProduct(
        String workspaceId,
        String recordKey,
        String title,
        String summary,
        String specification,
        String category,
        BigDecimal price,
        String status
    ) {
        SyncProduct product = require(workspaceId, recordKey);
        apply(product, title, summary, specification, category, price, status);
        product.setRevision(product.getRevision() + 1);
        product.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(product);
    }

    @Transactional
    @AIProcess(
        entityType = SyncProduct.ENTITY_TYPE,
        operation = AIProcessOperation.DELETE,
        indexingStrategy = IndexingStrategy.SYNC
    )
    public SyncProduct deleteProduct(String workspaceId, String recordKey) {
        SyncProduct product = require(workspaceId, recordKey);
        repository.delete(product);
        repository.flush();
        return product;
    }

    public List<SyncProduct> findAll(String workspaceId) {
        return repository.findAllByWorkspaceIdOrderByRecordKey(workspaceId);
    }

    public SyncProduct require(String workspaceId, String recordKey) {
        return repository.findByWorkspaceIdAndRecordKey(workspaceId, recordKey)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + recordKey));
    }

    private void apply(
        SyncProduct product,
        String title,
        String summary,
        String specification,
        String category,
        BigDecimal price,
        String status
    ) {
        product.setTitle(SyncEntitySupport.requireText(title, "title"));
        product.setSummary(SyncEntitySupport.requireText(summary, "summary"));
        product.setSpecification(SyncEntitySupport.requireText(specification, "specification"));
        product.setCategory(SyncEntitySupport.requireText(category, "category"));
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("price must be zero or greater");
        }
        product.setPrice(price);
        product.setStatus(SyncEntitySupport.requireText(status, "status").toUpperCase());
    }
}
