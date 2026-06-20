package com.ai.fabric.realapps.privacyfirst.service;

import ai.fabric.deletion.policy.UserDataDeletionProvider;
import com.ai.fabric.realapps.privacyfirst.repo.SupportMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SupportMessageDeletionProvider implements UserDataDeletionProvider {

    private static final String ENTITY_TYPE = "support-message";

    private final SupportMessageRepository repository;

    @Override
    public boolean canDeleteUser(String userId) {
        return userId != null && !userId.isBlank();
    }

    @Override
    @Transactional
    public int deleteUserDomainData(String userId) {
        return Math.toIntExact(repository.deleteByCustomerId(userId));
    }

    @Override
    public void notifyAfterDeletion(String userId) {
        // Domain app would notify downstream systems here. No-op for local demo.
    }

    @Override
    public List<UserEntityReference> findIndexedEntities(String userId) {
        return repository.findByCustomerId(userId).stream()
            .filter(message -> message.getId() != null)
            .map(message -> new UserEntityReference(ENTITY_TYPE, message.getId().toString()))
            .toList();
    }
}
