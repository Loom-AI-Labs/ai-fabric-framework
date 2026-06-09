package com.ai.fabric.realapps.chat.addresses.web;

import com.ai.fabric.realapps.chat.addresses.domain.Address;
import com.ai.fabric.realapps.chat.addresses.service.AddressService;
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
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public List<Address> list(@RequestParam("userId") String userId,
                              @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return addressService.listForUser(userId, limit);
    }

    @GetMapping("/{id}")
    public Address get(@PathVariable long id) {
        return addressService.get(id);
    }

    @PostMapping
    public ResponseEntity<Address> create(@Valid @RequestBody CreateAddressRequest request) {
        Address created = addressService.create(
            request.getUserId(),
            request.getType(),
            request.getLine1(),
            request.getLine2(),
            request.getCity(),
            request.getState(),
            request.getPostalCode(),
            request.getCountry(),
            request.isDefault()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Address update(@PathVariable long id, @Valid @RequestBody UpdateAddressRequest request) {
        return addressService.update(
            id,
            request.getType(),
            request.getLine1(),
            request.getLine2(),
            request.getCity(),
            request.getState(),
            request.getPostalCode(),
            request.getCountry(),
            request.getIsDefault()
        );
    }

    @DeleteMapping("/{id}")
    public Address delete(@PathVariable long id) {
        return addressService.delete(id);
    }

    @Data
    public static class CreateAddressRequest {
        @NotBlank
        private String userId;
        @NotNull
        private Address.Type type = Address.Type.SHIPPING;
        @NotBlank
        private String line1;
        private String line2;
        @NotBlank
        private String city;
        private String state;
        @NotBlank
        private String postalCode;
        @NotBlank
        private String country = "US";
        private boolean isDefault;
    }

    @Data
    public static class UpdateAddressRequest {
        private Address.Type type;
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String postalCode;
        private String country;
        private Boolean isDefault;
    }
}
