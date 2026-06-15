package ai.fabric.behavior.spi;

import ai.fabric.behavior.entity.BehaviorInsights;

import java.util.Optional;

/**
 * Optional user-implemented interface to fully customize behavior insight storage.
 * If provided, this becomes the single source of truth for read/write/delete operations.
 * 
 * Uses String userId for maximum flexibility - supports any ID format including:
 * - UUID strings: "550e8400-e29b-41d4-a716-446655440000"
 * - Email addresses: "user@example.com"
 * - Numeric IDs: "12345"
 * - Auth provider IDs: "auth0|abc123", "google-oauth2|xyz"
 * - Custom composite keys: "tenant:user:123"
 */
public interface BehaviorInsightStore {
    
    void save(BehaviorInsights insight);
    
    Optional<BehaviorInsights> findByUserId(String userId);
    
    void deleteByUserId(String userId);
}
