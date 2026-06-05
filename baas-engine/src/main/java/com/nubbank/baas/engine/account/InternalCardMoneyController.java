package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

/**
 * Internal (service-to-service) card money operations — Stage 5. Guarded by the
 * {@code @Order(0)} internal chain ({@code InternalServiceAuthFilter}, inbound HMAC).
 *
 * <p>{@code PartnerContext} is set HERE from the request body and cleared in
 * {@code finally}. It MUST be set before the {@code @Transactional} service call opens
 * the Hibernate session, or queries route to {@code public} (the documented pitfall).
 * That is why this controller is the context boundary and the service methods stay
 * {@code @Transactional} without touching context resolution.
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalCardMoneyController {

    private final AccountService accountService;

    @PostMapping("/card-debit")
    public ApiResponse<CardDebitResult> debit(@RequestBody CardDebitRequest req) {
        return inContext(req.partnerId(), req.schemaName(),
            () -> ApiResponse.ok(accountService.cardAuthorizationDebit(req)));
    }

    @PostMapping("/card-credit")
    public ApiResponse<CardCreditResult> credit(@RequestBody CardCreditRequest req) {
        return inContext(req.partnerId(), req.schemaName(),
            () -> ApiResponse.ok(accountService.cardAuthorizationCredit(req.authKey())));
    }

    @PostMapping("/account-lookup")
    public ApiResponse<AccountLookupResult> lookup(@RequestBody AccountLookupRequest req) {
        return inContext(req.partnerId(), req.schemaName(),
            () -> ApiResponse.ok(accountService.lookupAccount(req.accountId())));
    }

    private <T> T inContext(String partnerId, String schemaName, Supplier<T> body) {
        String env = schemaName != null && schemaName.startsWith("sandbox_") ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(partnerId, schemaName, "INTERNAL", env, "INTERNAL", null));
        try {
            return body.get();
        } finally {
            PartnerContext.clear();
        }
    }
}
