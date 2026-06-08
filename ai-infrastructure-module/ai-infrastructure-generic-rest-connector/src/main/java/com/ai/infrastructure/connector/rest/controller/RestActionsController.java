package com.ai.infrastructure.connector.rest.controller;

import com.ai.infrastructure.connector.rest.api.ActionExecuteRequestDto;
import com.ai.infrastructure.connector.rest.api.ActionResultDto;
import com.ai.infrastructure.connector.rest.service.RestActionExecutionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RestActionsController {

    private final RestActionExecutionService actionExecutionService;

    public RestActionsController(RestActionExecutionService actionExecutionService) {
        this.actionExecutionService = actionExecutionService;
    }

    @PostMapping(path = "/actions/execute")
    public ActionResultDto execute(@RequestBody ActionExecuteRequestDto request) {
        return actionExecutionService.execute(request);
    }
}

