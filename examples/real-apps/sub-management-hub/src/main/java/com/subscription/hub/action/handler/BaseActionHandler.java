package com.subscription.hub.action.handler;

import com.subscription.hub.service.UserService;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * Base class for action handlers with userId parsing support
 */
@RequiredArgsConstructor
public abstract class BaseActionHandler {

    protected final UserService userService;

    /**
     * Parse userId string - supports both numeric (1-100) and UUID formats
     */
    protected UUID parseUserId(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        try {
            // Try as numeric ID (1-100)
            Long numericId = Long.parseLong(userId);
            if (numericId < 1 || numericId > 100) {
                throw new IllegalArgumentException("Numeric userId must be between 1 and 100");
            }
            return userService.getUserIdFromNumeric(numericId);
        } catch (NumberFormatException e) {
            // Try as UUID string
            return UUID.fromString(userId);
        }
    }
}
