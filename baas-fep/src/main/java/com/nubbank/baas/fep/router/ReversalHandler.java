package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * Handles ISO 8583 Reversal Requests (MTI {@code 0400}).
 *
 * <p><strong>STUB — full reversal flow implemented in Task 6. Returns RC 96
 * (system error / not-yet-implemented) as a placeholder.</strong>
 *
 * <p>This stub depends only on {@link IsoMessageFactory}.  Do NOT add BinResolver,
 * CardClient, or any other Task-5/6 dependencies here — those arrive when this class
 * is rewritten in the appropriate task.
 *
 * <p><strong>PAN safety:</strong> this stub never logs any field values.
 */
@Component
@RequiredArgsConstructor
public class ReversalHandler {

    private final IsoMessageFactory iso;

    /**
     * STUB — full reversal flow implemented in Task 6. Returns RC 96 placeholder.
     *
     * @param req the incoming 0400 reversal request
     * @return a 0410 response with RC 96 (not yet implemented)
     */
    public ISOMsg handle(ISOMsg req) {
        // STUB — full reversal flow implemented in Task 6. Placeholder RC 96.
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        // NOTE: set(int, String) does NOT declare ISOException — no try/catch needed.
        resp.set(IsoField.RESPONSE_CODE, "96");
        return resp;
    }
}
