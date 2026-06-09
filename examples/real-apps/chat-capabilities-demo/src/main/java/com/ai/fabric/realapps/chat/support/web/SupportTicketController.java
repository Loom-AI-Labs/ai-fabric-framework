package com.ai.fabric.realapps.chat.support.web;

import com.ai.fabric.realapps.chat.support.domain.SupportTicket;
import com.ai.fabric.realapps.chat.support.service.SupportTicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class SupportTicketController {

    private final SupportTicketService supportTicketService;

    @GetMapping
    public List<SupportTicket> list(@RequestParam("userId") String userId,
                                    @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return supportTicketService.listForUser(userId, limit);
    }

    @GetMapping("/{id}")
    public SupportTicket get(@PathVariable long id) {
        return supportTicketService.get(id);
    }

    @PostMapping
    public ResponseEntity<SupportTicket> create(@Valid @RequestBody CreateTicketRequest request) {
        SupportTicket created = supportTicketService.create(
            request.getUserId(),
            request.getIssueType(),
            request.getDescription(),
            request.getOrderNumber()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public SupportTicket update(@PathVariable long id, @Valid @RequestBody UpdateTicketRequest request) {
        return supportTicketService.updateStatus(id, request.getStatus());
    }

    @DeleteMapping("/{id}")
    public SupportTicket delete(@PathVariable long id) {
        return supportTicketService.delete(id);
    }

    @Data
    public static class CreateTicketRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String issueType;
        @NotBlank
        private String description;
        private String orderNumber;
    }

    @Data
    public static class UpdateTicketRequest {
        private SupportTicket.Status status;
    }
}
