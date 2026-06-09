package com.ai.fabric.realapps.chat.addresses.service;

import com.ai.fabric.realapps.chat.addresses.domain.Address;
import com.ai.fabric.realapps.chat.addresses.repo.AddressRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;

    @Transactional(readOnly = true)
    public List<Address> listForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return addressRepository.findByUserIdOrderByCreatedAtDesc(userId.trim()).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional(readOnly = true)
    public Address get(long id) {
        return addressRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Address not found: " + id));
    }

    @Transactional
    public Address create(String userId,
                          Address.Type type,
                          String line1,
                          String line2,
                          String city,
                          String state,
                          String postalCode,
                          String country,
                          boolean isDefault) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(line1)) {
            throw new IllegalArgumentException("line1 is required");
        }
        if (!StringUtils.hasText(city)) {
            throw new IllegalArgumentException("city is required");
        }
        if (!StringUtils.hasText(postalCode)) {
            throw new IllegalArgumentException("postalCode is required");
        }
        if (!StringUtils.hasText(country)) {
            throw new IllegalArgumentException("country is required");
        }

        Address address = new Address();
        address.setUserId(userId.trim());
        address.setType(type != null ? type : Address.Type.SHIPPING);
        address.setLine1(line1.trim());
        address.setLine2(StringUtils.hasText(line2) ? line2.trim() : null);
        address.setCity(city.trim());
        address.setState(StringUtils.hasText(state) ? state.trim() : null);
        address.setPostalCode(postalCode.trim());
        address.setCountry(country.trim().toUpperCase());
        address.setDefault(isDefault);
        return addressRepository.save(address);
    }

    @Transactional
    public Address update(long id,
                          Address.Type type,
                          String line1,
                          String line2,
                          String city,
                          String state,
                          String postalCode,
                          String country,
                          Boolean isDefault) {
        Address address = get(id);

        if (type != null) {
            address.setType(type);
        }
        if (line1 != null) {
            address.setLine1(StringUtils.hasText(line1) ? line1.trim() : address.getLine1());
        }
        if (line2 != null) {
            address.setLine2(StringUtils.hasText(line2) ? line2.trim() : null);
        }
        if (city != null) {
            address.setCity(StringUtils.hasText(city) ? city.trim() : address.getCity());
        }
        if (state != null) {
            address.setState(StringUtils.hasText(state) ? state.trim() : null);
        }
        if (postalCode != null) {
            address.setPostalCode(StringUtils.hasText(postalCode) ? postalCode.trim() : address.getPostalCode());
        }
        if (country != null) {
            address.setCountry(StringUtils.hasText(country) ? country.trim().toUpperCase() : address.getCountry());
        }
        if (isDefault != null) {
            address.setDefault(isDefault);
        }

        return addressRepository.save(address);
    }

    @Transactional
    public Address delete(long id) {
        Address address = get(id);
        addressRepository.delete(address);
        return address;
    }
}
