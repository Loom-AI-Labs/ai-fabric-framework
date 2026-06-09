package com.subscription.hub.controller;

import com.subscription.hub.entity.User;
import com.subscription.hub.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Guest login and user management APIs")
public class GuestLoginController {

    private final UserRepository userRepository;
    private final Random random = new Random();

    @PostMapping("/guest/login")
    public ResponseEntity<Map<String, Object>> guestLogin() {
        // Get random user ID between 1 and 100
        Long randomUserId = (long) (random.nextInt(100) + 1);

        // Find user by userId
        User user = userRepository.findByUserId(randomUserId)
            .orElseThrow(() -> new RuntimeException("User not found: " + randomUserId));

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Guest assigned to user: {}", randomUserId);

        // Return user information
        Map<String, Object> response = new HashMap<>();
        response.put("userId", user.getUserId());
        response.put("userUuid", user.getId().toString());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("isGuest", true);
        response.put("message", "Guest login successful. Assigned to user " + randomUserId);

        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Get random user",
        description = "Returns information for a random user (1-100) without logging in. Useful for testing."
    )
    @ApiResponse(responseCode = "200", description = "Random user information")
    @GetMapping("/guest/random-user")
    public ResponseEntity<Map<String, Object>> getRandomUser() {
        // Get random user ID between 1 and 100
        Long randomUserId = (long) (random.nextInt(100) + 1);

        User user = userRepository.findByUserId(randomUserId)
            .orElseThrow(() -> new RuntimeException("User not found: " + randomUserId));

        Map<String, Object> response = new HashMap<>();
        response.put("userId", user.getUserId());
        response.put("userUuid", user.getId().toString());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());

        return ResponseEntity.ok(response);
    }
}
