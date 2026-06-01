package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * Handles ISO 8583 Financial Transaction Requests (MTI {@code 0200}).
 *
 * <p><strong>STUB — full cash-withdrawal / financial transaction flow implemented in
 * Task 6. Returns RC 96 (system error / not-yet-implemented) as a placeholder.</strong>
 *
 * <p>This stub depends only on {@link IsoMessageFactory}.  Do NOT add BinResolver,
 * CardClient, or any other Task-5/6 dependencies here — those arrive when this class
 * is rewritten in the appropriate task.
 *
 * <p><strong>PAN safety:</strong> this stub never logs any field values.
 */
@Component
@RequiredArgsConstructor
public class FinancialHandler {

    private final IsoMessageFactory iso;

    /**
     * STUB — full financial transaction flow implemented in Task 6. Returns RC 96 placeholder.
     *
     * @param req the incoming 0200 financial request
     * @return a 0210 response with RC 96 (not yet implemented)
     */
    public ISOMsg handle(ISOMsg req) {
        // STUB — full cash-withdrawal / financial transaction flow implemented in Task 6. Placeholder RC 96.
        ISOMsg resp = iso.create(MessageRouter.responseMti(MessageRouter.mti(req)));
        // NOTE: set(int, String) does NOT declare ISOException — no try/catch needed.
        resp.set(IsoField.RESPONSE_CODE, "96");
        return resp;
    }
}
