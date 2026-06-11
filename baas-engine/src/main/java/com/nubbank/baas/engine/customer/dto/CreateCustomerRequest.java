package com.nubbank.baas.engine.customer.dto;

import jakarta.validation.constraints.*;

public record CreateCustomerRequest(
    @NotBlank(message = "firstName is required") String firstName,
    @NotBlank(message = "lastName is required") String lastName,
    @Email(message = "email must be valid") String email,
    String phone,
    String dateOfBirth,
    String gender,
    @Size(max = 100) String externalReference,
    String bvn,
    String nin
) {}
