package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds ISO 20022 XML strings from Java message models.
 * Phase 1B: String-template based builder (no JAXB, no Apache Santuario).
 * Phase 2: Replace with JAXB + Apache Santuario for signing/encryption.
 * Reference: docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md
 */
@Slf4j
@Component
public class NpsXmlBuilder {

    private final String institutionName;
    private final String memberIdBicfi;
    private final String memberId;

    public NpsXmlBuilder(
            @Value("${baas.nps.institution-name}") String institutionName,
            @Value("${baas.nps.bicfi}") String memberIdBicfi,
            @Value("${baas.nps.member-id}") String memberId) {
        this.institutionName = institutionName;
        this.memberIdBicfi = memberIdBicfi;
        this.memberId = memberId;
    }

    public String buildAcmt023(Acmt023Message msg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<ns2:Document xmlns:ns2=\"urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04\">" +
            "<IdVrfctnReq>" +
            "<Assgnmt>" +
            "<MsgId>" + esc(msg.msgId()) + "</MsgId>" +
            "<CreDtTm>" + esc(msg.creDtTm()) + "</CreDtTm>" +
            "<Cretr><Pty><Nm>" + esc(msg.institutionName()) + "</Nm></Pty></Cretr>" +
            "<Assgnr>" +
            "<Pty><Nm>" + esc(msg.institutionName()) + "</Nm></Pty>" +
            "<Agt><FinInstnId>" +
            "<BICFI>" + esc(msg.instgAgtBicfi()) + "</BICFI>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></Agt></Assgnr>" +
            "<Assgne><Agt><FinInstnId>" +
            "<BICFI>" + esc(msg.destMmbId()) + "</BICFI>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.destMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></Agt></Assgne>" +
            "</Assgnmt>" +
            "<Vrfctn>" +
            "<Id>" + esc(msg.msgId()) + "</Id>" +
            "<PtyAndAcctId>" +
            "<Pty><Nm>" + esc(msg.beneficiaryName()) + "</Nm></Pty>" +
            "<Acct><Id><IBAN>" + esc(msg.beneficiaryAcct()) + "</IBAN></Id></Acct>" +
            "</PtyAndAcctId>" +
            "</Vrfctn>" +
            "</IdVrfctnReq>" +
            "</ns2:Document>";
    }

    public String buildPacs008(Pacs008Message msg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<ns2:Document xmlns:ns2=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12\">" +
            "<FIToFICstmrCdtTrf>" +
            "<GrpHdr>" +
            "<MsgId>" + esc(msg.msgId()) + "</MsgId>" +
            "<CreDtTm>" + esc(msg.creDtTm()) + "</CreDtTm>" +
            "<BtchBookg>false</BtchBookg><NbOfTxs>1</NbOfTxs>" +
            "<SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf>" +
            "<InstgAgt><FinInstnId><BICFI>" + esc(memberId) + "</BICFI>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></InstgAgt>" +
            "<InstdAgt><FinInstnId>" +
            "<ClrSysMmbId><MmbId>" + esc(msg.instdAgtMmbId()) + "</MmbId></ClrSysMmbId>" +
            "</FinInstnId></InstdAgt>" +
            "</GrpHdr>" +
            "<CdtTrfTxInf>" +
            "<PmtId>" +
            "<InstrId>" + esc(msg.instrId()) + "</InstrId>" +
            "<EndToEndId>" + esc(msg.endToEndId()) + "</EndToEndId>" +
            "<TxId>" + esc(msg.txId()) + "</TxId>" +
            "</PmtId>" +
            "<PmtTpInf>" +
            "<ClrChanl>RTNS</ClrChanl>" +
            "<SvcLvl><Prtry>0100</Prtry></SvcLvl>" +
            "<LclInstrm><Prtry>CTAA</Prtry></LclInstrm>" +
            "<CtgyPurp><Prtry>001</Prtry></CtgyPurp>" +
            "</PmtTpInf>" +
            "<IntrBkSttlmAmt Ccy=\"" + esc(msg.currency()) + "\">" +
            msg.amount().toPlainString() + "</IntrBkSttlmAmt>" +
            "<IntrBkSttlmDt>" + esc(msg.settlDt()) + "</IntrBkSttlmDt>" +
            "<ChrgBr>SLEV</ChrgBr>" +
            "<InstgAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></InstgAgt>" +
            "<InstdAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instdAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></InstdAgt>" +
            "<Dbtr><Nm>" + esc(msg.dbtrName()) + "</Nm></Dbtr>" +
            "<DbtrAcct><Id><IBAN>" + esc(msg.dbtrAcct()) + "</IBAN></Id>" +
            "<Nm>" + esc(msg.dbtrName()) + "</Nm></DbtrAcct>" +
            "<DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instgAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>" +
            "<CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>" + esc(msg.instdAgtMmbId()) +
            "</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>" +
            "<Cdtr><Nm>" + esc(msg.cdtrName()) + "</Nm></Cdtr>" +
            "<CdtrAcct><Id><IBAN>" + esc(msg.cdtrAcct()) + "</IBAN></Id>" +
            "<Nm>" + esc(msg.cdtrName()) + "</Nm></CdtrAcct>" +
            (msg.narration() != null ? "<RmtInf><Ustrd>" + esc(msg.narration()) + "</Ustrd></RmtInf>" : "") +
            "</CdtTrfTxInf>" +
            buildSplmtryData(msg) +
            "</FIToFICstmrCdtTrf>" +
            "</ns2:Document>";
    }

    private String buildSplmtryData(Pacs008Message msg) {
        return "<SplmtryData>" +
            "<PlcAndNm>AdditionalVerificationDetails</PlcAndNm>" +
            "<Envlp><CustomData>" +
            "<DebtorInfo>" +
            "<AccountDesignation>" + msg.dbtrAccountDesignation() + "</AccountDesignation>" +
            "<IdType>bvn</IdType>" +
            "<IdValue>" + esc(msg.dbtrBvn()) + "</IdValue>" +
            "<AccountTier>" + msg.dbtrAccountTier() + "</AccountTier>" +
            "</DebtorInfo><DebtorMetadata/>" +
            "<CreditorInfo>" +
            "<AccountDesignation>" + msg.cdtrAccountDesignation() + "</AccountDesignation>" +
            "<IdType>bvn</IdType>" +
            "<IdValue>" + esc(msg.cdtrBvn()) + "</IdValue>" +
            "<AccountTier>" + msg.cdtrAccountTier() + "</AccountTier>" +
            "</CreditorInfo><CreditorMetadata/>" +
            "<TransactionInfo>" +
            "<TransactionLocation>" + esc(msg.transactionLocation()) + "</TransactionLocation>" +
            "<NameEnquiryMsgId>" + esc(msg.nameEnquiryMsgId()) + "</NameEnquiryMsgId>" +
            "<ChannelCode>" + esc(msg.channelCode()) + "</ChannelCode>" +
            "</TransactionInfo>" +
            "</CustomData></Envlp>" +
            "</SplmtryData>";
    }

    private String esc(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
