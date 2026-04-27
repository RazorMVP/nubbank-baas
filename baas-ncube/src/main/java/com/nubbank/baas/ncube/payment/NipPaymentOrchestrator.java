package com.nubbank.baas.ncube.payment;

import com.nubbank.baas.ncube.common.NcubeException;
import com.nubbank.baas.ncube.payment.dto.*;
import com.nubbank.baas.ncube.payment.nps.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NipPaymentOrchestrator {

    private final NpsXmlBuilder xmlBuilder;
    private final NpsXmlParser xmlParser;
    private final NpsMessageSigner signer;
    private final NpsMessageEncryptor encryptor;
    private final NpsHttpClient httpClient;

    @Value("${baas.nps.institution-name}")
    private String institutionName;
    @Value("${baas.nps.bicfi}")
    private String memberIdBicfi;
    @Value("${baas.nps.member-id}")
    private String memberId;

    // Visible constructor for unit tests (bypasses @Value injection)
    public NipPaymentOrchestrator(NpsXmlBuilder xmlBuilder, NpsXmlParser xmlParser,
            NpsMessageSigner signer, NpsMessageEncryptor encryptor,
            NpsHttpClient httpClient,
            String institutionName, String memberIdBicfi, String memberId) {
        this.xmlBuilder = xmlBuilder;
        this.xmlParser = xmlParser;
        this.signer = signer;
        this.encryptor = encryptor;
        this.httpClient = httpClient;
        this.institutionName = institutionName;
        this.memberIdBicfi = memberIdBicfi;
        this.memberId = memberId;
    }

    public NipPaymentResponse initiate(NipPaymentRequest req, String authHeader) {
        String paymentId = uid();
        String e2eRef = uid();
        log.info("NIP initiated: id={} dest={} amount={}", paymentId, req.destinationAccountNumber(), req.amount());

        // Step 1: Name Enquiry (acmt.023 → acmt.024)
        String acmt023Id = uid();
        Acmt023Message acmt023 = new Acmt023Message(
            acmt023Id, Instant.now().toString(),
            memberId, memberIdBicfi, req.destinationBankCode(),
            institutionName, "", req.destinationAccountNumber());

        String acmt024Xml;
        try {
            acmt024Xml = httpClient.sendAcmt023(
                encryptor.encrypt(signer.sign(xmlBuilder.buildAcmt023(acmt023))));
        } catch (NcubeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NcubeException("NPS_ENQUIRY_ERROR",
                "Name enquiry failed for account " + req.destinationAccountNumber());
        }

        Acmt024Response acmt024 = xmlParser.parseAcmt024(acmt024Xml);
        if (!acmt024.verified()) {
            throw new NcubeException("BENEFICIARY_ACCOUNT_NOT_FOUND",
                "Account " + req.destinationAccountNumber() + " could not be verified at destination bank");
        }

        // Step 2: Credit Transfer (pacs.008 → pacs.002)
        Pacs008Message pacs008 = new Pacs008Message(
            paymentId, Instant.now().toString(),
            memberId, req.destinationBankCode(),
            paymentId + "-INSTR", e2eRef, paymentId,
            req.amount(), req.currency() != null ? req.currency() : "NGN",
            LocalDate.now().toString(),
            "", req.sourceAccountId(), req.debtorBvn(),
            req.debtorAccountTier(), req.debtorAccountDesignation(),
            acmt024.beneficiaryName(), req.destinationAccountNumber(), acmt024.beneficiaryBvn(),
            acmt024.accountTier(), acmt024.accountDesignation(),
            req.narration() != null ? req.narration() : "",
            acmt024.nameEnquiryMsgId(),
            req.channelCode() != null ? req.channelCode() : "1",
            "00000000000N000000000000E");

        String pacs002Xml;
        try {
            pacs002Xml = httpClient.sendPacs008(
                encryptor.encrypt(signer.sign(xmlBuilder.buildPacs008(pacs008))));
        } catch (NcubeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NcubeException("NPS_PAYMENT_ERROR", "Payment submission failed: " + ex.getMessage());
        }

        Pacs002Response pacs002 = xmlParser.parsePacs002(pacs002Xml);
        String status = pacs002.isAccepted() ? "COMPLETED" : "FAILED";
        log.info("NIP {}: id={} txStatus={}", status, paymentId, pacs002.txStatus());

        return new NipPaymentResponse(paymentId, status, e2eRef, pacs002.rejectReason());
    }

    private String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }
}
