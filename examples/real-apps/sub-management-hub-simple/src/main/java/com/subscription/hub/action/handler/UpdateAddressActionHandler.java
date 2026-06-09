package com.subscription.hub.action.handler;

import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionAllowed;
import com.ai.infrastructure.intent.action.annotation.ActionConfirmation;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import com.subscription.hub.entity.Address;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "update_address",
    description = "Update billing or shipping address",
    category = "subscription",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class UpdateAddressActionHandler extends BaseActionHandler {

    private final SubscriptionService subscriptionService;

    @Autowired(required = false)
    private PIIDetectionService piiDetectionService;

    public UpdateAddressActionHandler(SubscriptionService subscriptionService,
                                     UserService userService) {
        super(userService);
        this.subscriptionService = subscriptionService;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            UUID userUuid = parseUserId(userId);
            return subscriptionService.hasActiveSubscription(userUuid);
        } catch (Exception e) {
            return false;
        }
    }

    @ActionConfirmation
    public String confirm(@Param(value = "addressType", description = "BILLING or SHIPPING") String addressType) {
        String type = addressType != null && !addressType.isBlank() ? addressType : "BILLING";
        return String.format(
            "Are you sure you want to update your %s address?",
            type.toLowerCase()
        );
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subscriptionId", required = true, description = "UUID of the subscription") String subscriptionId,
        @Param(value = "addressType", description = "BILLING or SHIPPING") String addressType,
        @Param(value = "streetAddress", required = true, description = "Street address") String streetAddress,
        @Param(value = "city", required = true, description = "City") String city,
        @Param(value = "state", required = true, description = "State/Province") String state,
        @Param(value = "postalCode", required = true, description = "Postal/ZIP code") String postalCode,
        @Param(value = "country", required = true, description = "Country") String country,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            String type = addressType != null && !addressType.isBlank() ? addressType : "BILLING";
            Address.AddressType parsedType = Address.AddressType.valueOf(type.toUpperCase());

            // Build address from parameters
            Address address = Address.builder()
                .streetAddress(streetAddress)
                .city(city)
                .state(state)
                .postalCode(postalCode)
                .country(country)
                .type(parsedType)
                .build();

            // Validate address using PII detection service (if available)
            if (piiDetectionService != null) {
                String addressString = String.format("%s, %s, %s %s, %s",
                    address.getStreetAddress(),
                    address.getCity(),
                    address.getState(),
                    address.getPostalCode(),
                    address.getCountry()
                );

                var piiResult = piiDetectionService.detectAndProcess(addressString);
                address.setIsValidated(piiResult.isPiiDetected() == false); // Valid if no PII issues
                address.setValidationScore(piiResult.isPiiDetected() ? 0.5 : 1.0);
            } else {
                // Default validation if PII service not available
                address.setIsValidated(true);
                address.setValidationScore(1.0);
            }

            var subscription = subscriptionService.updateAddress(
                UUID.fromString(subscriptionId),
                parsedType,
                address
            );

            return ActionResult.builder()
                .success(true)
                .message("Your address has been updated successfully")
                .data(ActionResultContracts.object(Map.of(
                    "subscriptionId", subscriptionId,
                    "addressType", addressType.toString(),
                    "isValidated", address.getIsValidated().toString()
                )))
                .build();
        } catch (Exception e) {
            log.error("Error updating address", e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to update address. " + e.getMessage())
                .errorCode("UPDATE_ADDRESS_FAILED")
                .build();
        }
    }
}
