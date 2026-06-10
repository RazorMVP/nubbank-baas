package com.nubbank.baas.card.stats;

import com.nubbank.baas.card.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEF-1C-29 (card side) — cards-issued count for the engine dashboard. Guarded by the
 * {@code @Order(1)} internal chain ({@code InternalServiceAuthFilter}, inbound HMAC) — no auth
 * annotation needed here.
 */
@RestController
@RequestMapping("/internal/v1/stats")
@RequiredArgsConstructor
public class CardStatsController {

    private final CardStatsService cardStatsService;

    @PostMapping
    public ApiResponse<CardStatsResponse> stats(@RequestBody CardStatsRequest request) {
        return ApiResponse.ok(new CardStatsResponse(
            cardStatsService.cardsIssued(request.partnerId(), request.schemaName())));
    }
}
