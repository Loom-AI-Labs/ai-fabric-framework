package com.ai.fabric.runtime.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SuggestionsResponse {
    private boolean success;
    private String message;
    private List<String> suggestions;
    private String raw;
    private RuntimeAuthContextResponse authContext;
}
