package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import com.nubbank.baas.fep.routing.BinResolver;
import com.nubbank.baas.fep.routing.CardClient;
import com.nubbank.baas.fep.routing.PartnerRoute;
import com.nubbank.baas.fep.routing.ReversalDecision;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handles ISO 8583 Reversal Requests (MTI {@code 0400}) — F6.
 *
 * <p>Resolves the partner via DE2→BIN, parses DE90 (Original Data Elements) for the
 * original STAN + transmission date-time, and asks Card to locate + reverse the original
 * authorization:
 * <ul>
 *   <li>unrouteable BIN → RC {@code 91}, DE2 omitted (PAN never echoed).</li>
 *   <li>DE90 missing/short → RC {@code 30} (format error), no Card call.</li>
 *   <li>original located → RC {@code 00}; not located (incl. fail-closed) → RC {@code 25}.</li>
 * </ul>
 * Phase 1C reverses no funds (balance is stubbed); this only flips the original's
 * reversed flag and removes the prior blanket-approve defect. DE2 is read only for
 * routing and is NEVER echoed on the response.
 */
@Component
@RequiredArgsConstructor
public class ReversalHandler {

    private final BinResolver       binResolver;
    private final CardClient        cardClient;
    private final IsoMessageFactory iso;

    public ISOMsg handle(ISOMsg req) {
        String pan = field(req, IsoField.PAN);
        Optional<PartnerRoute> route = binResolver.resolve(pan);
        if (route.isEmpty()) {
            return respond(req, "91");   // unrouteable — DE2 omitted
        }

        String de90 = field(req, IsoField.ORIGINAL_DATA);
        if (de90 == null || de90.length() < 20) {
            return respond(req, "30");   // format error — cannot identify the original
        }
        String originalStan = de90.substring(4, 10);
        String originalDts  = de90.substring(10, 20);
        String terminalId   = field(req, IsoField.TERMINAL_ID);

        ReversalDecision decision = cardClient.reverse(new ReversalDecision.Request(
            route.get().partnerId().toString(),
            route.get().schemaName(),
            originalStan,
            terminalId,
            originalDts));

        return respond(req, decision.located() ? "00" : "25");
    }

    private ISOMsg respond(ISOMsg req, String rc) {
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        if (req.hasField(IsoField.STAN)) {
            resp.set(IsoField.STAN, req.getString(IsoField.STAN));
        }
        if (req.hasField(IsoField.TRANSMISSION_DTS)) {
            resp.set(IsoField.TRANSMISSION_DTS, req.getString(IsoField.TRANSMISSION_DTS));
        }
        MessageRouter.set(resp, IsoField.RESPONSE_CODE, rc);
        return resp;
    }

    private static String field(ISOMsg m, int f) {
        return m.hasField(f) ? m.getString(f) : null;
    }
}
