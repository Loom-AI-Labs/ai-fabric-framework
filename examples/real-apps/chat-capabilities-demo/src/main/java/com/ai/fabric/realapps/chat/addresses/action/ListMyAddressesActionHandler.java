package com.ai.fabric.realapps.chat.addresses.action;

import com.ai.fabric.realapps.chat.addresses.domain.Address;
import com.ai.fabric.realapps.chat.addresses.service.AddressService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "list_my_addresses",
    description = "List my saved shipping/billing addresses",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class ListMyAddressesActionHandler {

    private final AddressService addressService;

    @ActionExecute
    public ActionResult execute(@Param(value = "limit", description = "Max number of addresses to return") Integer limit,
                                ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            int effectiveLimit = limit != null ? limit : 20;
            List<Address> addresses = addressService.listForUser(userId, effectiveLimit);

            List<Map<String, Object>> payload = addresses.stream()
                .filter(a -> a != null)
                .map(a -> Map.<String, Object>of(
                    "addressId", a.getId(),
                    "type", a.getType() != null ? a.getType().name() : null,
                    "line1", a.getLine1(),
                    "line2", a.getLine2(),
                    "city", a.getCity(),
                    "state", a.getState(),
                    "postalCode", a.getPostalCode(),
                    "country", a.getCountry(),
                    "isDefault", a.isDefault()
                ))
                .toList();

            return ActionResult.builder()
                .success(true)
                .message(payload.isEmpty() ? "No saved addresses found" : "Saved addresses")
                .data(ActionResultContracts.list(payload))
                .build();
        } catch (Exception e) {
            log.error("List my addresses failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to fetch addresses: " + e.getMessage())
                .errorCode("LIST_MY_ADDRESSES_FAILED")
                .build();
        }
    }
}
