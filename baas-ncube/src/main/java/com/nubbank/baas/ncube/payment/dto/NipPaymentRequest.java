package com.nubbank.baas.ncube.payment.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record NipPaymentRequest(
    @NotBlank String sourceAccountId,
    @NotBlank @Size(min = 10, max = 10) String destinationAccountNumber,
    @NotBlank @Size(min = 3, max = 6) String destinationBankCode,
    @NotNull @DecimalMin("1.00") BigDecimal amount,
    String currency,
    String narration,
    @NotBlank @Size(min = 11, max = 11) String debtorBvn,
    int debtorAccountTier,
    int debtorAccountDesignation,
    String channelCode
) {}
