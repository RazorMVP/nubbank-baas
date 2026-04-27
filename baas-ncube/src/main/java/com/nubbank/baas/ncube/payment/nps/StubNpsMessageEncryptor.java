package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "baas.nps.encryption.enabled", havingValue = "false", matchIfMissing = true)
public class StubNpsMessageEncryptor implements NpsMessageEncryptor {
    @Override
    public String encrypt(String signedXml) {
        log.debug("STUB NpsMessageEncryptor: XML unencrypted (Phase 2 activates AES-256-GCM + RSA-OAEP)");
        return signedXml;
    }
}
