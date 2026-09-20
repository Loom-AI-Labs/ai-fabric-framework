package com.ai.fabric.realapps.chat.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.policies.domain.Policy;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import com.ai.fabric.realapps.chat.promotions.repo.CouponRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class DemoSeedDataRunnerTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private ApplicationArguments applicationArguments;

    @InjectMocks
    private DemoSeedDataRunner runner;

    @Test
    void seedsTheCanonicalReturnPolicy() {
        when(policyRepository.existsByTitleIgnoreCase("Returns and refunds")).thenReturn(false);

        runner.run(applicationArguments);

        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(policyRepository).save(policyCaptor.capture());
        assertThat(policyCaptor.getValue().getTitle()).isEqualTo("Returns and refunds");
        assertThat(policyCaptor.getValue().getClassification()).isEqualTo("PUBLIC");
        assertThat(policyCaptor.getValue().getText()).contains("30 days");
    }

    @Test
    void keepsAnExistingCanonicalReturnPolicy() {
        when(policyRepository.existsByTitleIgnoreCase("Returns and refunds")).thenReturn(true);

        runner.run(applicationArguments);

        verify(policyRepository, never()).save(any());
    }
}
