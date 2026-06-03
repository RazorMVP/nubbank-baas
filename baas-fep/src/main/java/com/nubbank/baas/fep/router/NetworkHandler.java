package com.nubbank.baas.fep.router;

import com.nubbank.baas.fep.iso.IsoField;
import com.nubbank.baas.fep.iso.IsoMessageFactory;
import lombok.RequiredArgsConstructor;
import org.jpos.iso.ISOMsg;
import org.springframework.stereotype.Component;

/**
 * Handles ISO 8583 Network Management requests (MTI {@code 0800}).
 *
 * <p>Behaviour per the plan spec:
 * <ul>
 *   <li>Creates a {@code 0810} Network Management Response.</li>
 *   <li>Sets DE39 (Response Code) to {@code "00"} (approved/OK).</li>
 *   <li>Echoes DE70 (Network Management Code), DE11 (STAN), and DE7 (Transmission
 *       Date/Time) from the request when present — absent fields are silently skipped.</li>
 * </ul>
 *
 * <p><strong>PAN safety:</strong> network management messages do not carry a PAN;
 * this handler never logs any field values regardless.
 */
@Component
@RequiredArgsConstructor
public class NetworkHandler {

    private final IsoMessageFactory iso;

    /**
     * Handle a {@code 0800} Network Management Request and return a {@code 0810} response.
     *
     * @param req the incoming 0800 message
     * @return the outbound 0810 response
     */
    public ISOMsg handle(ISOMsg req) {
        ISOMsg resp = iso.create("0810");
        // Echo DE70 (Network Mgmt Code), DE11 (STAN), DE7 (Transmission DTS) when present.
        echoIfPresent(req, resp,
                IsoField.NETWORK_MGMT_CODE,
                IsoField.STAN,
                IsoField.TRANSMISSION_DTS);
        // NOTE: set(int, String) does NOT declare ISOException — no try/catch needed.
        resp.set(IsoField.RESPONSE_CODE, "00");
        return resp;
    }

    /**
     * Copy each listed field from {@code req} to {@code resp} if it is present on the request.
     *
     * @param req    source message
     * @param resp   destination message
     * @param fields varargs list of DE field numbers to echo
     */
    private void echoIfPresent(ISOMsg req, ISOMsg resp, int... fields) {
        for (int f : fields) {
            if (req.hasField(f)) {
                resp.set(f, req.getString(f));
            }
        }
    }
}
