package com.nubbank.baas.ncube.payment.nps;

import com.nubbank.baas.ncube.common.NcubeException;
import com.nubbank.baas.ncube.payment.NipPaymentOrchestrator;
import com.nubbank.baas.ncube.payment.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NipPaymentOrchestratorTest {

    @Mock private NpsXmlBuilder xmlBuilder;
    @Mock private NpsXmlParser xmlParser;
    @Mock private NpsMessageSigner signer;
    @Mock private NpsMessageEncryptor encryptor;
    @Mock private NpsHttpClient httpClient;

    private NipPaymentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new NipPaymentOrchestrator(
            xmlBuilder, xmlParser, signer, encryptor, httpClient,
            "NubBank BaaS", "999058", "999058");
    }

    @Test
    void initiate_approvedPayment_returnsCompleted() throws Exception {
        when(xmlBuilder.buildAcmt023(any())).thenReturn("<acmt023/>");
        when(signer.sign(any())).thenReturn("<signed/>");
        when(encryptor.encrypt(any())).thenReturn("<encrypted/>");
        when(httpClient.sendAcmt023(any())).thenReturn("<acmt024/>");
        when(xmlParser.parseAcmt024(any())).thenReturn(
            new Acmt024Response("acmt024-id", "acmt023-id", "acmt024-id",
                "Jane Receiver", "98765432109", 1, 1, true));
        when(xmlBuilder.buildPacs008(any())).thenReturn("<pacs008/>");
        when(signer.sign("<pacs008/>")).thenReturn("<pacs008-signed/>");
        when(encryptor.encrypt("<pacs008-signed/>")).thenReturn("<pacs008-encrypted/>");
        when(httpClient.sendPacs008(any())).thenReturn("<pacs002/>");
        when(xmlParser.parsePacs002(any())).thenReturn(
            new Pacs002Response("pacs002-id", "pacs008-id", "ACSC", null));

        NipPaymentRequest req = new NipPaymentRequest(
            "src-account-id", "0581000099", "058",
            new BigDecimal("5000.00"), "NGN", "Payment ref",
            "12345678901", 1, 1, "1");
        NipPaymentResponse response = orchestrator.initiate(req, "Bearer jwt");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.paymentId()).isNotBlank();
        assertThat(response.rejectReason()).isNull();
    }

    @Test
    void initiate_rejectedPayment_returnsFailed() throws Exception {
        when(xmlBuilder.buildAcmt023(any())).thenReturn("<acmt023/>");
        when(signer.sign(any())).thenReturn("<signed/>");
        when(encryptor.encrypt(any())).thenReturn("<encrypted/>");
        when(httpClient.sendAcmt023(any())).thenReturn("<acmt024/>");
        when(xmlParser.parseAcmt024(any())).thenReturn(
            new Acmt024Response("id", "id", "id", "Jane", "987", 1, 1, true));
        when(xmlBuilder.buildPacs008(any())).thenReturn("<pacs008/>");
        when(signer.sign("<pacs008/>")).thenReturn("<pacs008-signed/>");
        when(encryptor.encrypt("<pacs008-signed/>")).thenReturn("<pacs008-encrypted/>");
        when(httpClient.sendPacs008(any())).thenReturn("<pacs002/>");
        when(xmlParser.parsePacs002(any())).thenReturn(
            new Pacs002Response("id", "id", "RJCT", "AC01 — Incorrect account number"));

        NipPaymentRequest req = new NipPaymentRequest(
            "src-id", "9999999999", "058",
            new BigDecimal("5000.00"), "NGN", "Bad payment",
            "12345678901", 1, 1, "1");
        NipPaymentResponse response = orchestrator.initiate(req, "Bearer jwt");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.rejectReason()).contains("AC01");
    }

    @Test
    void initiate_unverifiedBeneficiary_throwsNcubeException() throws Exception {
        when(xmlBuilder.buildAcmt023(any())).thenReturn("<acmt023/>");
        when(signer.sign(any())).thenReturn("<signed/>");
        when(encryptor.encrypt(any())).thenReturn("<encrypted/>");
        when(httpClient.sendAcmt023(any())).thenReturn("<acmt024/>");
        when(xmlParser.parseAcmt024(any())).thenReturn(
            new Acmt024Response("id", "id", "id", "", "", 0, 0, false));

        NipPaymentRequest req = new NipPaymentRequest(
            "src-id", "9999999999", "058",
            new BigDecimal("5000.00"), "NGN", "Invalid",
            "12345678901", 1, 1, "1");

        assertThatThrownBy(() -> orchestrator.initiate(req, "Bearer jwt"))
            .isInstanceOf(NcubeException.class)
            .hasMessageContaining("9999999999");
    }
}
