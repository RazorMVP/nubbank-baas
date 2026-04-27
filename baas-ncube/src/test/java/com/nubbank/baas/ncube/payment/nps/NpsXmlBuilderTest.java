package com.nubbank.baas.ncube.payment.nps;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class NpsXmlBuilderTest {

    private NpsXmlBuilder xmlBuilder;

    @BeforeEach
    void setUp() {
        xmlBuilder = new NpsXmlBuilder("NubBank BaaS", "999058", "999058");
    }

    @Test
    void buildAcmt023_containsCorrectNamespace() {
        Acmt023Message msg = new Acmt023Message(
            "MSG001", "2026-04-27T10:00:00Z",
            "999058", "999058", "058",
            "NubBank BaaS", "John Doe", "0581000042");
        String xml = xmlBuilder.buildAcmt023(msg);
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04");
        assertThat(xml).contains("IdVrfctnReq");
    }

    @Test
    void buildAcmt023_containsMsgId() {
        Acmt023Message msg = new Acmt023Message(
            "MSG-TEST-001", "2026-04-27T10:00:00Z",
            "999058", "999058", "058",
            "NubBank BaaS", "John Doe", "0581000042");
        String xml = xmlBuilder.buildAcmt023(msg);
        assertThat(xml).contains("<MsgId>MSG-TEST-001</MsgId>");
    }

    @Test
    void buildAcmt023_containsBeneficiaryAccount() {
        Acmt023Message msg = new Acmt023Message(
            "MSG001", "2026-04-27T10:00:00Z",
            "999058", "999058", "058",
            "NubBank BaaS", "Jane Doe", "0581000099");
        String xml = xmlBuilder.buildAcmt023(msg);
        assertThat(xml).contains("<IBAN>0581000099</IBAN>");
        assertThat(xml).contains("<Nm>Jane Doe</Nm>");
    }

    @Test
    void buildPacs008_containsCorrectNamespace() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-001");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12");
        assertThat(xml).contains("FIToFICstmrCdtTrf");
    }

    @Test
    void buildPacs008_containsAmountAndCurrency() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-002");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("Ccy=\"NGN\"");
        assertThat(xml).contains("100000.00");
    }

    @Test
    void buildPacs008_containsBvnInSplmtryData() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-003");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("<IdValue>12345678901</IdValue>");
        assertThat(xml).contains("<IdValue>98765432109</IdValue>");
        assertThat(xml).contains("<IdType>bvn</IdType>");
        assertThat(xml).contains("AdditionalVerificationDetails");
    }

    @Test
    void buildPacs008_containsNameEnquiryMsgId() {
        Pacs008Message msg = buildSamplePacs008("MSG-PAY-004");
        String xml = xmlBuilder.buildPacs008(msg);
        assertThat(xml).contains("<NameEnquiryMsgId>ACMT024-REF-001</NameEnquiryMsgId>");
    }

    private Pacs008Message buildSamplePacs008(String msgId) {
        return new Pacs008Message(
            msgId, "2026-04-27T10:00:00Z", "999058", "058",
            msgId + "-INSTR", msgId + "-E2E", msgId,
            new BigDecimal("100000.00"), "NGN", "2026-04-27",
            "John Sender", "0581000001", "12345678901", 1, 1,
            "Jane Receiver", "0581000099", "98765432109", 1, 1,
            "Payment for invoice 001", "ACMT024-REF-001", "1",
            "01080652440N020900337921E");
    }
}
