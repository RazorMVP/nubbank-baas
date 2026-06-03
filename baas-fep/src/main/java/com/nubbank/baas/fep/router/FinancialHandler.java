package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * Handles ISO 8583 Financial Transaction Requests (MTI {@code 0200}).
 *
 * <p>Phase 1C: The 0200 financial-request flow is identical to the 0100 authorization
 * flow — BIN route → Card authorize → DE39 response.  This handler delegates entirely
 * to {@link AuthorizationHandler} to avoid duplicating that logic.
 *
 * <p>Because {@link AuthorizationHandler#handle(ISOMsg)} derives the response MTI from
 * the incoming request MTI via {@link MessageRouter#responseMti(String)}, a {@code 0200}
 * request correctly produces a {@code 0210} response.
 *
 * <p>Withdrawal-specific processing (settlement advice, partial-reversal matching) is
 * deferred to Phase 2.
 *
 * <p><strong>PAN safety:</strong> all PAN-safety invariants are enforced inside
 * {@link AuthorizationHandler} — this class adds no logging or field manipulation.
 */
@Component
@RequiredArgsConstructor
public class FinancialHandler {

    private final AuthorizationHandler authorizationHandler;

    /**
     * Handle an incoming ISO 8583 financial request by delegating to
     * {@link AuthorizationHandler}.
     *
     * <p>Phase 1C: same BIN-route → authorize → DE39 flow as 0100.
     * Withdrawal-specific settlement/advice processing deferred to Phase 2.
     *
     * @param req the incoming 0200 financial request
     * @return a 0210 response derived from the delegated authorization flow
     */
    public ISOMsg handle(ISOMsg req) {
        return authorizationHandler.handle(req);
    }
}
