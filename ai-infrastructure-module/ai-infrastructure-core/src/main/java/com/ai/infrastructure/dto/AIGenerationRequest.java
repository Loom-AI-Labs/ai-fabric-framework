package com.ai.infrastructure.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AIGenerationRequest
 * 
 * Request DTO for AI content generation operations.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIGenerationRequest {
    
    @NotNull(message = "Entity ID is required")
    private String entityId;
    
    @NotNull(message = "Entity type is required")
    private String entityType;
    
    @NotNull(message = "Generation type is required")
    private String generationType;
    
    private String prompt;
    
    private String context;
    
    private String systemPrompt;

    /**
     * Optional multi-message chat history to send to chat-capable LLM providers.
     *
     * <p>When present, providers MUST send these as native chat messages (role/user/assistant),
     * and MUST NOT pack them into the {@link #prompt} string.</p>
     */
    private List<AIChatMessage> messages;

    /**
     * Optional provider-native input parts that supplement the text prompt.
     *
     * <p>FILE_URL parts are transient by contract. Providers must not log or persist URL values,
     * and unsupported providers must fail closed with explicit documentUsage evidence.</p>
     */
    private List<AIGenerationInputPart> inputParts;

    private TransientInputPolicy transientInputPolicy;
    
    private String purpose;
    
    private Map<String, Object> parameters;
    
    private String model;
    
    private Integer maxTokens;
    
    private Double temperature;

    private AIAccessSubjectContext authContext;
}
