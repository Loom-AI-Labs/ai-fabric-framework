package com.ai.fabric.realapps.privacyfirst.service;

import ai.fabric.deletion.policy.UserDataDeletionProvider.UserEntityReference;
import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.repo.SupportMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportMessageDeletionProviderTest {

    private final SupportMessageRepository repository = mock(SupportMessageRepository.class);
    private final SupportMessageDeletionProvider provider = new SupportMessageDeletionProvider(repository);

    @Test
    void findsIndexedSupportMessagesForCustomer() {
        SupportMessage first = message(1L);
        SupportMessage second = message(2L);
        when(repository.findByCustomerId("cust-1001")).thenReturn(List.of(first, second));

        List<UserEntityReference> references = provider.findIndexedEntities("cust-1001");

        assertThat(references)
            .extracting(UserEntityReference::entityId)
            .containsExactly("1", "2");
        assertThat(references)
            .extracting(UserEntityReference::entityType)
            .containsOnly("support-message");
    }

    @Test
    void deletesCustomerDomainMessages() {
        when(repository.deleteByCustomerId("cust-1001")).thenReturn(3L);

        assertThat(provider.deleteUserDomainData("cust-1001")).isEqualTo(3);
    }

    private static SupportMessage message(long id) {
        SupportMessage message = new SupportMessage();
        message.setId(id);
        message.setCustomerId("cust-1001");
        return message;
    }
}
