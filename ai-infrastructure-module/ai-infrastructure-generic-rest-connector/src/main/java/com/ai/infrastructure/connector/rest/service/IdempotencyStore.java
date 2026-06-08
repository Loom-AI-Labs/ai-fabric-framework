package com.ai.infrastructure.connector.rest.service;

import com.ai.infrastructure.connector.rest.api.ActionResultDto;

import java.util.function.Supplier;

public interface IdempotencyStore {

    ActionResultDto executeIdempotent(String actionId, String idempotencyKey, Supplier<ActionResultDto> execution);
}

