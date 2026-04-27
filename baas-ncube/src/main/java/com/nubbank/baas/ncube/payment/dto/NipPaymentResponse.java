package com.nubbank.baas.ncube.payment.dto;

public record NipPaymentResponse(
    String paymentId, String status, String reference, String rejectReason
) {}
