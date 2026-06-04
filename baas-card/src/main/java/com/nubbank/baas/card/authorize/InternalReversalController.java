package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.authorize.dto.ReversalRequest;
import com.nubbank.baas.card.authorize.dto.ReversalResponse;
import com.nubbank.baas.card.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (service-to-service) reversal endpoint (F6). {@code POST /internal/v1/reversal}.
 *
 * <p>No auth annotation: the {@code @Order(1)} internal chain runs
 * {@code InternalServiceAuthFilter} (inbound HMAC) which 401s any unsigned call before
 * it reaches here. {@link ReversalService} sets/clears the tenant context.
 */
@RestController
@RequestMapping("/internal/v1/reversal")
@RequiredArgsConstructor
public class InternalReversalController {

    private final ReversalService reversalService;

    @PostMapping
    public ApiResponse<ReversalResponse> reverse(@RequestBody ReversalRequest request) {
        return ApiResponse.ok(reversalService.reverse(request));
    }
}
