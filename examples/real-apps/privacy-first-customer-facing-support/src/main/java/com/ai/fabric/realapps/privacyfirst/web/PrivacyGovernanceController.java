package com.ai.fabric.realapps.privacyfirst.web;

import ai.fabric.deletion.UserDataDeletionResult;
import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/support/privacy")
@RequiredArgsConstructor
public class PrivacyGovernanceController {

    private final PrivacyGovernanceService privacyGovernanceService;

    @GetMapping("/customers/{customerId}/inventory")
    public PrivacyGovernanceService.CustomerPrivacyInventory inventory(@PathVariable String customerId) {
        return privacyGovernanceService.inventory(customerId);
    }

    @PostMapping("/customers/{customerId}/delete")
    public UserDataDeletionResult deleteCustomer(@PathVariable String customerId) {
        return privacyGovernanceService.deleteCustomer(customerId);
    }

    @GetMapping("/search")
    public List<SupportMessagesController.SupportMessageSummaryResponse> search(
        @RequestParam("q") String query,
        @RequestParam(value = "limit", defaultValue = "5") int limit
    ) {
        List<SupportMessage> messages = privacyGovernanceService.searchSafeMessages(query, limit);
        return messages.stream()
            .map(SupportMessagesController.SupportMessageSummaryResponse::from)
            .toList();
    }
}
