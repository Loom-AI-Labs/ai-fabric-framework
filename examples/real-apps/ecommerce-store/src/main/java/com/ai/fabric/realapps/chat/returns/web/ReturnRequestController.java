package com.ai.fabric.realapps.chat.returns.web;

import com.ai.fabric.realapps.chat.returns.domain.ReturnRequest;
import com.ai.fabric.realapps.chat.returns.service.ReturnRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
public class ReturnRequestController {

    private final ReturnRequestService returnRequestService;

    @GetMapping
    public List<ReturnRequest> list(@RequestParam("userId") String userId,
                                    @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return returnRequestService.listForUser(userId, limit);
    }

    @GetMapping("/{id}")
    public ReturnRequest get(@PathVariable long id) {
        return returnRequestService.get(id);
    }

    @PostMapping
    public ResponseEntity<ReturnRequest> create(@Valid @RequestBody CreateReturnRequest request) {
        ReturnRequest created = returnRequestService.create(
            request.getUserId(),
            request.getOrderNumber(),
            request.getReason()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ReturnRequest update(@PathVariable long id, @Valid @RequestBody UpdateReturnRequest request) {
        return returnRequestService.updateStatus(id, request.getStatus(), request.getEligible());
    }

    @DeleteMapping("/{id}")
    public ReturnRequest delete(@PathVariable long id) {
        return returnRequestService.delete(id);
    }

    @Data
    public static class CreateReturnRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String orderNumber;
        @NotBlank
        private String reason;
    }

    @Data
    public static class UpdateReturnRequest {
        private ReturnRequest.Status status;
        private Boolean eligible;
    }
}
