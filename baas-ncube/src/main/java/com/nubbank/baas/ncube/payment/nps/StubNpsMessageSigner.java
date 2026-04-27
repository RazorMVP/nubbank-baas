package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "baas.nps.signing.enabled", havingValue = "false", matchIfMissing = true)
public class StubNpsMessageSigner implements NpsMessageSigner {
    @Override
    public String sign(String xmlDocument) {
        log.debug("STUB NpsMessageSigner: XML unsigned (Phase 2 activates RSA-SHA256 XMLDSig)");
        return xmlDocument;
    }
}
