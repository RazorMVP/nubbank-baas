package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * Handles ISO 8583 Reversal Requests (MTI {@code 0400}).
 *
 * <p><strong>Phase 1C STUB — approve all reversals.</strong>
 * Real reversal matching (find the original authorization by STAN/RRN, reverse the
 * balance hold, and post a reversal GL entry) is deferred to Phase 2 (DEF-1C-25).
 *
 * <p>Note: DE90 (Original Data Elements) is NOT defined in the Phase-1C packager
 * ({@code iso8583-1987-fields.xml} covers DE1–DE70 only).  DE90 is therefore not
 * read from the request and not set on the response — that processing belongs to
 * Phase 2 along with the full reversal-matching logic.
 *
 * <p><strong>PAN safety:</strong> this handler never reads or logs any field values
 * that could contain a PAN.  DE2 is not touched.
 */
@Component
@RequiredArgsConstructor
public class ReversalHandler {

    private final IsoMessageFactory iso;

    /**
     * Handle an incoming ISO 8583 reversal request.
     *
     * <p>STUB approve — real reversal (match + reverse original, DE90) deferred to
     * Phase 2 (DEF-1C-25).
     *
     * @param req the incoming 0400 reversal request
     * @return a 0410 response with RC {@code "00"} (approved) and echoed STAN / TRANSMISSION_DTS
     */
    public ISOMsg handle(ISOMsg req) {
        // STUB approve — real reversal (match + reverse original, DE90) deferred to Phase 2 (DEF-1C-25).
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        if (req.hasField(IsoField.STAN)) {
            resp.set(IsoField.STAN, req.getString(IsoField.STAN));
        }
        if (req.hasField(IsoField.TRANSMISSION_DTS)) {
            resp.set(IsoField.TRANSMISSION_DTS, req.getString(IsoField.TRANSMISSION_DTS));
        }
        // STUB approve — real reversal deferred to Phase 2 (DEF-1C-25)
        resp.set(IsoField.RESPONSE_CODE, "00");
        return resp;
    }
}
