package com.nubbank.baas.ncube.payment.nps;

/**
 * Interface for RSA-SHA256 XML digital signature (XMLDSig).
 * Phase 1B: stub returns XML unchanged.
 * Phase 2: replace with Apache Santuario using NIBSS-provided RSA key pair.
 * See: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md §2.1
 */
public interface NpsMessageSigner {
    String sign(String xmlDocument);
}
