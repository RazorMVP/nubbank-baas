package com.nubbank.baas.ncube.identity.dto;

public record VerificationResponse(
    String identifier, boolean verified,
    String firstName, String lastName,
    String dateOfBirth, String phoneNumber,
    String verificationSource
) {}
