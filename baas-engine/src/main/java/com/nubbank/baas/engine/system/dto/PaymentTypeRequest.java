package com.nubbank.baas.engine.system.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentTypeRequest(@NotBlank String name, String description,
    Boolean isCashPayment, Integer position) {}
