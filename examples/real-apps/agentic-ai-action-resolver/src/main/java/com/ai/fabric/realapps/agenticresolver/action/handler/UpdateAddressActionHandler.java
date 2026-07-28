package com.ai.fabric.realapps.agenticresolver.action.handler;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;
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

            UUID resolvedSubscriptionId = requireActiveSubscriptionId(subscriptionService, context);
            var subscription = subscriptionService.updateAddress(
                resolvedSubscriptionId,
                parsedType,
                address
            );

            return ActionResult.builder()
                .success(true)
                .message("Your address has been updated successfully")
                .data(ActionResultContracts.object(Map.of(
                    "subscriptionId", resolvedSubscriptionId.toString(),
                    "addressType", type,
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
