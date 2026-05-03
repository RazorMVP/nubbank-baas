package com.nubbank.baas.engine.clientext.dto;

public record AddressRequest(String addressType, String street, String city,
    String stateProvince, String countryCode, String postalCode) {}
