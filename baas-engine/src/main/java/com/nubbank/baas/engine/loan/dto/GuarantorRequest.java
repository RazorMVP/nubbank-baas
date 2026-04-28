package com.nubbank.baas.engine.loan.dto;

import java.util.UUID;

public record GuarantorRequest(
    String guarantorType,
    UUID customerId,
    String firstName,
    String lastName,
    String email,
    String phone
) {}
