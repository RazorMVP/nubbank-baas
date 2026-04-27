package com.nubbank.baas.ncube.payment.nps;

/**
 * Interface for AES-256-GCM + RSA-OAEP XML encryption (XMLEnc).
 * Phase 1B: stub returns XML unchanged.
 * Phase 2: replace with Apache Santuario using NIBSS-provided public key.
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md §2.2
 */
public interface NpsMessageEncryptor {
    String encrypt(String signedXml);
}
