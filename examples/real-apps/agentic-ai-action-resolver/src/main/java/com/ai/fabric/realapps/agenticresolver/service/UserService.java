package com.ai.fabric.realapps.agenticresolver.service;

import com.ai.fabric.realapps.agenticresolver.entity.User;
import com.ai.fabric.realapps.agenticresolver.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    /**
     * Convert numeric userId (1-100) to User UUID
     */
    public UUID getUserIdFromNumeric(Long numericUserId) {
        User user = userRepository.findByUserId(numericUserId)
            .orElseThrow(() -> new RuntimeException("User not found: " + numericUserId));
        return user.getId();
    }

    /**
     * Get user by numeric ID
     */
    public Optional<User> getUserByNumericId(Long numericUserId) {
        return userRepository.findByUserId(numericUserId);
    }

    /**
     * Get user by UUID
     */
    public Optional<User> getUserById(UUID userId) {
        return userRepository.findById(userId);
    }
}
