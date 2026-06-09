package com.ai.fabric.realapps.chat.accounts.service;

import com.ai.fabric.realapps.chat.accounts.domain.Account;
import com.ai.fabric.realapps.chat.accounts.repo.AccountRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<Account> list(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return accountRepository.findAll().stream().limit(effectiveLimit).toList();
    }

    @Transactional(readOnly = true)
    public Account get(long id) {
        return accountRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Account not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<Account> findByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        return accountRepository.findByUserId(userId.trim());
    }

    @Transactional
    public Account create(String userId, String name, String email, String tier, String segment) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name is required");
        }
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("email is required");
        }

        String normalizedUserId = userId.trim();
        if (accountRepository.existsByUserId(normalizedUserId)) {
            throw new IllegalArgumentException("account already exists for userId: " + normalizedUserId);
        }

        Account account = new Account();
        account.setUserId(normalizedUserId);
        account.setName(name.trim());
        account.setEmail(email.trim());
        account.setTier(StringUtils.hasText(tier) ? tier.trim() : "standard");
        account.setSegment(StringUtils.hasText(segment) ? segment.trim() : "general");
        return accountRepository.save(account);
    }

    @Transactional
    public Account update(long id, String name, String email, String tier, String segment) {
        Account account = get(id);

        if (name != null) {
            account.setName(StringUtils.hasText(name) ? name.trim() : account.getName());
        }
        if (email != null) {
            account.setEmail(StringUtils.hasText(email) ? email.trim() : account.getEmail());
        }
        if (tier != null) {
            account.setTier(StringUtils.hasText(tier) ? tier.trim() : account.getTier());
        }
        if (segment != null) {
            account.setSegment(StringUtils.hasText(segment) ? segment.trim() : account.getSegment());
        }

        return accountRepository.save(account);
    }

    @Transactional
    public Account delete(long id) {
        Account account = get(id);
        accountRepository.delete(account);
        return account;
    }
}
