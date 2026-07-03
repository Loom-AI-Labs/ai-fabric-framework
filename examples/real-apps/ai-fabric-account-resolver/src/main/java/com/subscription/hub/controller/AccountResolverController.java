package com.subscription.hub.controller;

import com.subscription.hub.entity.Address;
import com.subscription.hub.entity.PaymentMethod;
import com.subscription.hub.entity.RefundRequest;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.DeploymentInfoService;
import com.subscription.hub.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/account-resolver")
@RequiredArgsConstructor
public class AccountResolverController {

    private final AccountResolutionService accountResolutionService;
    private final SubscriptionService subscriptionService;
    private final DeploymentInfoService deploymentInfoService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return deploymentInfoService.health();
    }

    @GetMapping("/policies")
    public List<AccountResolutionService.ResolutionPolicy> policies() {
        return accountResolutionService.policies();
    }

    @GetMapping("/scenarios")
    public List<AccountResolutionService.ResolverScenario> scenarios() {
        return accountResolutionService.scenarios();
    }

    @PostMapping("/demo/seed")
    public Map<String, AccountResolutionService.AccountReadiness> seedDemoScenarios() {
        return accountResolutionService.seedDemoScenarios();
    }

    @GetMapping("/users/{userId}/readiness")
    public AccountResolutionService.AccountReadiness readinessByUser(@PathVariable Long userId) {
        return accountResolutionService.inspectReadiness(userId);
    }

    @GetMapping("/subscriptions/{subscriptionId}/readiness")
    public AccountResolutionService.AccountReadiness readinessBySubscription(@PathVariable UUID subscriptionId) {
        return accountResolutionService.inspectReadiness(subscriptionId);
    }

    @PostMapping("/subscriptions/{subscriptionId}/payment-method")
    public AccountResolutionService.PaymentMethodResult updatePaymentMethod(
        @PathVariable UUID subscriptionId,
        @Valid @RequestBody UpdatePaymentMethodRequest request
    ) {
        return accountResolutionService.updatePaymentMethod(
            subscriptionId,
            request.type(),
            request.provider(),
            request.last4()
        );
    }

    @PutMapping("/subscriptions/{subscriptionId}/billing-address")
    public AccountResolutionService.AccountReadiness updateBillingAddress(
        @PathVariable UUID subscriptionId,
        @Valid @RequestBody UpdateAddressRequest request
    ) {
        Address address = Address.builder()
            .streetAddress(request.streetAddress())
            .city(request.city())
            .state(request.state())
            .postalCode(request.postalCode())
            .country(request.country())
            .type(Address.AddressType.BILLING)
            .isValidated(true)
            .validationScore(1.0)
            .build();
        subscriptionService.updateAddress(subscriptionId, Address.AddressType.BILLING, address);
        return accountResolutionService.inspectReadiness(subscriptionId);
    }

    @PostMapping("/subscriptions/{subscriptionId}/refund")
    public ResponseEntity<AccountResolutionService.RefundResolutionResult> requestRefund(
        @PathVariable UUID subscriptionId,
        @Valid @RequestBody RefundRequestBody request
    ) {
        return ResponseEntity.ok(accountResolutionService.requestRefund(
            subscriptionId,
            request.amount(),
            request.reason(),
            request.resolutionType()
        ));
    }

    public record UpdatePaymentMethodRequest(
        PaymentMethod.PaymentType type,
        @NotBlank String provider,
        @NotBlank @Pattern(regexp = ".*\\d{4}.*") String last4
    ) { }

    public record UpdateAddressRequest(
        @NotBlank String streetAddress,
        @NotBlank String city,
        @NotBlank String state,
        @NotBlank String postalCode,
        @NotBlank String country
    ) { }

    public record RefundRequestBody(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        String reason,
        RefundRequest.ResolutionType resolutionType
    ) { }
}
