package com.nubbank.baas.ncube.payment.nps;

import com.nubbank.baas.ncube.common.NcubeException;

/**
 * Interface for HTTP communication with NIBSS NPS endpoint.
 * Phase 1B: stub returns realistic mock responses.
 * Phase 2: replace with real HTTPS calls to NPS using participant credentials.
 * NPS endpoints (assigned by NIBSS on participant registration):
 *   POST https://<nps-ip>:8022/nps/acmt/023 — Name Enquiry
 *   POST https://<nps-ip>:8022/nps/pacs/008 — Credit Transfer
 */
public interface NpsHttpClient {
    String sendAcmt023(String signedEncryptedXml) throws NcubeException;
    String sendPacs008(String signedEncryptedXml) throws NcubeException;
}
