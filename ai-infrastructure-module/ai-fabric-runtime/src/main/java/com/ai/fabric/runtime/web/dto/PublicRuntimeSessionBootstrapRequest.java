package com.ai.fabric.runtime.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class PublicRuntimeSessionBootstrapRequest {

    @JsonIgnore
    @Schema(hidden = true)
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
