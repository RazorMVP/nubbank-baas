package com.nubbank.baas.ncube.identity.dto;

import jakarta.validation.constraints.*;

public record BvnVerificationRequest(
    @NotBlank
    @Size(min = 11, max = 11, message = "BVN must be exactly 11 digits")
    @Pattern(regexp = "\\d{11}", message = "BVN must contain only digits")
    String bvn
) {}
