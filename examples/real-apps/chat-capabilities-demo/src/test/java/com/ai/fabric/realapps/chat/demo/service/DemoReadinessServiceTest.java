package com.ai.fabric.realapps.chat.demo.service;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.repository.IndexingQueueRepository;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import com.ai.fabric.realapps.chat.promotions.repo.CouponRepository;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import com.ai.fabric.realapps.chat.support.repo.SupportTicketRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoReadinessServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
    private final VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
    private final IndexingQueueRepository indexingQueueRepository = mock(IndexingQueueRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<VectorDatabaseService> vectorProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<IndexingQueueRepository> queueProvider = mock(ObjectProvider.class);

    private final DemoReadinessService readinessService = new DemoReadinessService(
        productRepository,
        reviewRepository,
        policyRepository,
        couponRepository,
        supportTicketRepository,
        vectorProvider,
        queueProvider
    );

    @Test
    void readinessIncludesRetrievalProofForLoadedVectorSpaces() {
        when(productRepository.count()).thenReturn(100L);
        when(reviewRepository.count()).thenReturn(0L);
        when(policyRepository.count()).thenReturn(20L);
        when(couponRepository.count()).thenReturn(0L);
        when(supportTicketRepository.count()).thenReturn(0L);
        when(vectorProvider.getIfAvailable()).thenReturn(vectorDatabaseService);
        when(queueProvider.getIfAvailable()).thenReturn(indexingQueueRepository);
        when(indexingQueueRepository.count()).thenReturn(0L);
        when(vectorDatabaseService.vectorProviderName()).thenReturn("test-vector");
        when(vectorDatabaseService.vectorSearchFilterMode()).thenReturn("native");
        when(vectorDatabaseService.vectorScanFilterMode()).thenReturn("scan");
        when(vectorDatabaseService.getVectorCountByEntityType("product")).thenReturn(100L);
        when(vectorDatabaseService.getVectorCountByEntityType("review")).thenReturn(0L);
        when(vectorDatabaseService.getVectorCountByEntityType("policy")).thenReturn(20L);
        when(vectorDatabaseService.scan(any(VectorScanRequest.class))).thenAnswer(invocation -> {
            VectorScanRequest request = invocation.getArgument(0, VectorScanRequest.class);
            if ("product".equals(request.getEntityType())) {
                return VectorScanPage.builder()
                    .vectors(List.of(VectorRecord.builder()
                        .vectorId("vector-product-1")
                        .entityType("product")
                        .entityId("SKU-LAP-9001")
                        .build()))
                    .build();
            }
            return VectorScanPage.builder()
                .vectors(List.of())
                .build();
        });

        DemoReadinessService.ReadinessReport report = readinessService.readiness();

        assertThat(report.getStage()).isEqualTo("products");
        assertThat(report.getVectorSpaces().get("product").getRetrievalProof().isChecked()).isTrue();
        assertThat(report.getVectorSpaces().get("product").getRetrievalProof().isFound()).isTrue();
        assertThat(report.getVectorSpaces().get("product").getRetrievalProof().getSampleEntityId()).isEqualTo("SKU-LAP-9001");
        assertThat(report.getVectorSpaces().get("review").getRetrievalProof().isChecked()).isFalse();
        assertThat(report.getVectorSpaces().get("policy").getRetrievalProof().isChecked()).isTrue();
        assertThat(report.getVectorSpaces().get("policy").getRetrievalProof().isFound()).isFalse();
        assertThat(report.getWarnings()).contains("Policies exist in the database but policy vector retrieval proof failed.");
    }
}
