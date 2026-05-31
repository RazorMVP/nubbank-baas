package com.nubbank.baas.card.bin;

import com.nubbank.baas.card.bin.dto.BinLookupResponse;
import com.nubbank.baas.card.common.ApiResponse;
import com.nubbank.baas.card.common.BaasException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (service-to-service) BIN lookup — FROZEN FEP contract §2.
 *
 * {@code GET /internal/v1/bins/{bin}} → 200 with ApiResponse data
 * {@code { partnerId, schemaName }}, or 404 ({@code BIN_NOT_FOUND}).
 *
 * No auth annotation here: the {@code @Order(1)} internal security chain runs
 * {@code InternalServiceAuthFilter} (inbound HMAC), which 401s any unsigned call
 * before it reaches this controller. Runs with NO PartnerContext (tenant-less
 * caller); the public-schema {@link CardBinRange} is reachable anyway.
 */
@RestController
@RequestMapping("/internal/v1/bins")
@RequiredArgsConstructor
public class InternalBinController {

    private final BinService binService;

    @GetMapping("/{bin}")
    public ApiResponse<BinLookupResponse> lookup(@PathVariable String bin) {
        return binService.lookup(bin)
            .map(b -> ApiResponse.ok(new BinLookupResponse(b.getPartnerId(), b.getSchemaName())))
            .orElseThrow(() -> BaasException.notFound("BIN_NOT_FOUND", "No BIN range matches " + bin));
    }
}
