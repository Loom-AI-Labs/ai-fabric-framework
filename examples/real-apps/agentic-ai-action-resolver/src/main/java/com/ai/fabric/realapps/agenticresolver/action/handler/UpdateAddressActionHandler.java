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
import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;

import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "update_address",
    description = "Update billing or shipping address",
    category = "subscription",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class UpdateAddressActionHandler extends BaseActionHandler {

    private final SubscriptionService subscriptionService;

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
    public String confirm(
        @Param(
            value = "addressType",
            description = "BILLING or SHIPPING",
            allowedValues = {"BILLING", "SHIPPING"}
        ) String addressType
    ) {
        String type = addressType != null && !addressType.isBlank() ? addressType : "BILLING";
        return String.format(
            "Are you sure you want to update your %s address?",
            type.toLowerCase()
        );
    }

    @ActionExecute
    public ActionResult execute(
        @Param(
            value = "addressType",
            description = "BILLING or SHIPPING",
            allowedValues = {"BILLING", "SHIPPING"}
        ) String addressType,
        @Param(
            value = "streetAddress",
            required = true,
            description = "Street address, at most 200 characters",
            pattern = "(?s).{1,200}"
        ) String streetAddress,
        @Param(
            value = "city",
            required = true,
            description = "City, at most 100 characters",
            pattern = "(?s).{1,100}"
        ) String city,
        @Param(
            value = "state",
            required = true,
            description = "State or province, at most 100 characters",
            pattern = "(?s).{1,100}"
        ) String state,
        @Param(
            value = "postalCode",
            required = true,
            description = "Postal or ZIP code, at most 32 characters",
            pattern = "(?s).{1,32}"
        ) String postalCode,
        @Param(
            value = "country",
            required = true,
            description = "Country, at most 100 characters",
            pattern = "(?s).{1,100}"
        ) String country,
        ActionContext context
    ) {
        String type = addressType != null && !addressType.isBlank()
            ? addressType
            : "BILLING";
        Address.AddressType parsedType = Address.AddressType.valueOf(
            type.toUpperCase()
        );

        Address address = Address.builder()
            .streetAddress(streetAddress.trim())
            .city(city.trim())
            .state(state.trim())
            .postalCode(postalCode.trim())
            .country(country.trim())
            .type(parsedType)
            .build();

        // The demo's account system owns address validation. PII detection is
        // a privacy control and must not be mistaken for postal validation.
        address.setIsValidated(true);
        address.setValidationScore(1.0);

        UUID resolvedSubscriptionId = requireActiveSubscriptionId(
            subscriptionService,
            context
        );
        subscriptionService.updateAddress(
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
    }
}
