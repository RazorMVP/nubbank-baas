package com.nubbank.baas.engine.twofa.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record VerifyOtpRequest(@NotNull UUID tokenId, @NotBlank String otp) {}
