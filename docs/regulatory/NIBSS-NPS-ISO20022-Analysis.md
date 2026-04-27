# NIBSS National Payment Stack (NPS) — ISO 20022 Analysis

**Source:** https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main  
**Version:** NPS v1.2 (evolving — additional message types will be introduced)  
**Analysed:** 2026-04-27  
**Purpose:** Confirm NubBank BaaS architecture alignment with NPS requirements before Phase 1B implementation

---

## 1. What NPS Actually Is

The **National Payment Stack** is a bank-to-bank **ISO 20022 XML switch** hosted by NIBSS. It is **not** a REST API. All communication is via `application/xml` over HTTPS on a NIBSS-assigned dedicated IP and port:

```
https://<nps-ip-assigned-by-nibss>:8022/nps/{message-type}/{version}
```

Examples:
- `POST https://<nps-ip>:8022/nps/pacs/008` — credit transfer
- `POST https://<nps-ip>:8022/nps/acmt/023` — name enquiry
- `GET  https://<nps-ip>:8022/nps/participants` — participant list

NubBank BaaS acts as the **originating bank** for its partners' payments. Partners send REST JSON to `baas-ncube`, which translates to ISO 20022 XML and sends to NPS. The XML complexity is fully hidden from partners.

---

## 2. Security Requirements — Non-Negotiable

Every XML message sent to NPS must be **both signed AND encrypted**. The final message has no indentation (minified XML).

### 2.1 Digital Signature (XMLDSig)

| Parameter | Value |
|-----------|-------|
| Algorithm | RSA-SHA256 |
| Namespace | `http://www.w3.org/2001/04/xmldsig-more#rsa-sha256` |
| Canonicalization | Exclusive C14N — `http://www.w3.org/2001/10/xml-exc-c14n#` |
| Transform | Enveloped signature — `http://www.w3.org/2000/09/xmldsig#enveloped-signature` |
| Digest | SHA-256 — `http://www.w3.org/2001/04/xmlenc#sha256` |

### 2.2 Content Encryption (XMLEnc)

| Parameter | Value |
|-----------|-------|
| Content encryption | AES-256-GCM — `http://www.w3.org/2009/xmlenc11#aes256-gcm` |
| Key encryption | RSA-OAEP-MGF1P — `http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p` |

**Java library required:** Apache Santuario (`xml-security-c` / `xmlsec`) — the only production-grade Java library that handles both XMLDSig and XMLEnc for ISO 20022.

### 2.3 Message Ordering

Every message is first **signed** (enveloped signature), then the signed content is **encrypted**. The outer wrapper remains unencrypted to allow NPS routing.

---

## 3. Complete Message Type Inventory

| # | ISO 20022 Type | Direction | Purpose | NubBank Phase |
|---|---------------|-----------|---------|--------------|
| 1 | `acmt.023.001.04` | Bank → NPS → Beneficiary Bank | **Name Enquiry** — mandatory verification before every payment | Phase 2 |
| 2 | `acmt.024` | Beneficiary Bank → NPS → Bank | Name Enquiry Response (account name, BVN, tier, NameEnquiryMsgId) | Phase 2 |
| 3 | `pacs.008.001.12` | Bank → NPS → Beneficiary Bank | **Credit Transfer** — the actual NIP payment message | Phase 2 |
| 4 | `pacs.002` | Beneficiary Bank → NPS → Bank | Payment Status (ACSC=approved, RJCT=rejected) | Phase 2 |
| 5 | `pacs.028` | Bank → NPS | Payment Status Request (check pending payment) | Phase 3 |
| 6 | `pacs.003` | Creditor Bank → NPS → Debtor Bank | FI to FI Direct Debit | Phase 3 |
| 7 | `pain.009` | Creditor Bank → NPS → Debtor Bank | Mandate Initiation (direct debit setup) | Phase 3 |
| 8 | `pain.010` | Creditor Bank → NPS | Mandate Amendment | Phase 3 |
| 9 | `pain.011` | Creditor Bank → NPS | Mandate Cancellation | Phase 3 |
| 10 | `pain.012` | Debtor Bank → NPS → Creditor Bank | Mandate Acceptance Response | Phase 3 |
| 11 | `pain.013` | Creditor Bank → NPS → Debtor Bank | Request to Pay (RTP) | Phase 3 |
| 12 | `pain.014` | Debtor Bank → NPS → Creditor Bank | Response to Request to Pay | Phase 3 |
| 13 | `camt.060` | Bank → NPS → Beneficiary Bank | Account Reporting Request | Phase 3 |
| 14 | `camt.052` | Beneficiary Bank → NPS → Bank | Intraday Account Report | Phase 3 |
| 15 | `camt.053` | Beneficiary Bank → NPS → Bank | End-of-Day Statement | Phase 3 |
| 16 | Get Participants | Bank → NPS | List of active NPS participants (REST GET, no body) | Phase 2 |

> **Note from NIBSS:** "This is an evolving document. Additional message types will be introduced in future to support use cases."

---

## 4. The NIP Credit Transfer Flow — Step by Step

Every NIP payment requires **two sequential NPS calls**. The Name Enquiry (`acmt.023/024`) is **mandatory** before any `pacs.008`. The `NameEnquiryMsgId` from the acmt.024 response must be embedded in the pacs.008 `SplmtryData`.

```
Partner REST call:
  POST /baas/v1/payments/nip
  { sourceAccountId, destinationAccountNumber, destinationBankCode,
    amount, currency, description }

Step 1 — Name Enquiry:
  baas-ncube builds acmt.023 (XML)
  baas-ncube signs acmt.023 (RSA-SHA256)
  baas-ncube encrypts acmt.023 (AES-256-GCM)
  baas-ncube → POST https://nps-ip:8022/nps/acmt/023
  NPS validates and routes to Beneficiary Bank
  Beneficiary Bank responds with acmt.024
  NPS delivers acmt.024 to baas-ncube

  baas-ncube extracts from acmt.024:
    - beneficiary_name
    - beneficiary_bvn
    - account_tier
    - name_enquiry_msg_id  ← MUST be included in pacs.008

Step 2 — Credit Transfer:
  baas-ncube builds pacs.008 (XML)
    - Includes debtor BVN (from Customer.bvn_encrypted)
    - Includes creditor BVN (from acmt.024)
    - Includes NameEnquiryMsgId (from acmt.024)
    - Includes AccountTier for both parties
  baas-ncube signs pacs.008 (RSA-SHA256)
  baas-ncube encrypts pacs.008 (AES-256-GCM)
  baas-ncube → POST https://nps-ip:8022/nps/pacs/008
  NPS validates and routes to Beneficiary Bank
  Beneficiary Bank responds with pacs.002 (ACSC or RJCT)
  NPS delivers pacs.002 to baas-ncube

baas-ncube → Partner REST response:
  { paymentId, status: "COMPLETED" | "FAILED", reference }
```

---

## 5. pacs.008 Message Structure (Full Annotated XML)

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>{unique-msg-id}</MsgId>             <!-- Mandatory: unique per message -->
      <CreDtTm>{ISO-8601-datetime}</CreDtTm>      <!-- Mandatory -->
      <BtchBookg>false</BtchBookg>               <!-- Mandatory: fixed false -->
      <NbOfTxs>1</NbOfTxs>                       <!-- Mandatory: fixed 1 -->
      <SttlmInf>
        <SttlmMtd>CLRG</SttlmMtd>               <!-- Mandatory: fixed CLRG -->
      </SttlmInf>
      <InstgAgt>
        <FinInstnId>
          <BICFI>{NubBank-BICFI}</BICFI>          <!-- Conditional -->
          <ClrSysMmbId>
            <MmbId>{NubBank-MmbId}</MmbId>        <!-- Mandatory: CBN sort code -->
          </ClrSysMmbId>
        </FinInstnId>
      </InstgAgt>
      <InstdAgt>
        <FinInstnId>
          <ClrSysMmbId>
            <MmbId>{destination-bank-MmbId}</MmbId> <!-- Mandatory -->
          </ClrSysMmbId>
        </FinInstnId>
      </InstdAgt>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <InstrId>{instruction-id}</InstrId>         <!-- Mandatory -->
        <EndToEndId>{e2e-reference}</EndToEndId>     <!-- Mandatory -->
        <TxId>{same-as-MsgId}</TxId>               <!-- Mandatory -->
      </PmtId>
      <PmtTpInf>
        <ClrChanl>RTNS</ClrChanl>                  <!-- Optional: fixed RTNS -->
        <SvcLvl><Prtry>0100</Prtry></SvcLvl>       <!-- Optional: priority -->
        <LclInstrm><Prtry>CTAA</Prtry></LclInstrm> <!-- Optional: local instrument -->
        <CtgyPurp><Prtry>001</Prtry></CtgyPurp>    <!-- Optional: category purpose -->
      </PmtTpInf>
      <IntrBkSttlmAmt Ccy="NGN">{amount-2dp}</IntrBkSttlmAmt>  <!-- Mandatory -->
      <IntrBkSttlmDt>{ISO-8601-date}</IntrBkSttlmDt>           <!-- Mandatory -->
      <ChrgBr>SLEV</ChrgBr>                         <!-- Optional: fixed SLEV -->
      <Dbtr><Nm>{sender-name}</Nm></Dbtr>           <!-- Mandatory -->
      <DbtrAcct>
        <Id><IBAN>{sender-NUBAN}</IBAN></Id>         <!-- Mandatory: NUBAN goes here -->
        <Nm>{sender-name}</Nm>
      </DbtrAcct>
      <DbtrAgt>
        <FinInstnId>
          <ClrSysMmbId><MmbId>{NubBank-MmbId}</MmbId></ClrSysMmbId>
        </FinInstnId>
      </DbtrAgt>
      <CdtrAgt>
        <FinInstnId>
          <ClrSysMmbId><MmbId>{dest-bank-MmbId}</MmbId></ClrSysMmbId>
        </FinInstnId>
      </CdtrAgt>
      <Cdtr><Nm>{beneficiary-name}</Nm></Cdtr>       <!-- Mandatory -->
      <CdtrAcct>
        <Id><IBAN>{beneficiary-NUBAN}</IBAN></Id>    <!-- Mandatory: NUBAN goes here -->
        <Nm>{beneficiary-name}</Nm>
      </CdtrAcct>
      <RmtInf>
        <Ustrd>{narration-max-140-chars}</Ustrd>    <!-- Optional -->
      </RmtInf>
    </CdtTrfTxInf>

    <!-- Nigerian-specific extension block — populated from acmt.024 + debtor KYC -->
    <SplmtryData>
      <PlcAndNm>AdditionalVerificationDetails</PlcAndNm>  <!-- Mandatory: fixed value -->
      <Envlp>
        <CustomData>
          <DebtorInfo>
            <AccountDesignation>1</AccountDesignation>    <!-- Mandatory: 1=Individual -->
            <IdType>bvn</IdType>                          <!-- Mandatory -->
            <IdValue>{debtor-BVN}</IdValue>               <!-- Mandatory: from Customer entity -->
            <AccountTier>1</AccountTier>                  <!-- Mandatory: CBN tier 1/2/3 -->
          </DebtorInfo>
          <DebtorMetadata/>
          <CreditorInfo>
            <AccountDesignation>1</AccountDesignation>    <!-- Mandatory: from acmt.024 -->
            <IdType>bvn</IdType>                          <!-- Mandatory -->
            <IdValue>{creditor-BVN}</IdValue>             <!-- Mandatory: from acmt.024 -->
            <AccountTier>1</AccountTier>                  <!-- Mandatory: from acmt.024 -->
          </CreditorInfo>
          <CreditorMetadata/>
          <TransactionInfo>
            <TransactionLocation>{GPS-coordinates}</TransactionLocation>  <!-- Mandatory -->
            <NameEnquiryMsgId>{acmt024-msg-id}</NameEnquiryMsgId>         <!-- Mandatory: from acmt.024 -->
            <ChannelCode>1</ChannelCode>                                  <!-- Mandatory: 1=internet -->
            <RiskRating>R000000000000000000B9</RiskRating>               <!-- Optional -->
          </TransactionInfo>
        </CustomData>
      </Envlp>
    </SplmtryData>
  </FIToFICstmrCdtTrf>
</ns2:Document>
```

> **Important**: `IBAN` tag is reused for Nigerian NUBAN account numbers (10 digits). This is NPS convention — not actual IBAN format.

---

## 6. acmt.023 Message Structure (Name Enquiry)

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04">
  <IdVrfctnReq>
    <Assgnmt>
      <MsgId>{unique-msg-id}</MsgId>                    <!-- Mandatory -->
      <CreDtTm>{ISO-8601-datetime}</CreDtTm>            <!-- Mandatory -->
      <Cretr>
        <Pty><Nm>{NubBank-name}</Nm></Pty>             <!-- Mandatory -->
      </Cretr>
      <Assgnr>
        <Pty><Nm>{NubBank-name}</Nm></Pty>
        <Agt>
          <FinInstnId>
            <BICFI>{NubBank-BICFI}</BICFI>
            <ClrSysMmbId><MmbId>{NubBank-MmbId}</MmbId></ClrSysMmbId>
          </FinInstnId>
        </Agt>
      </Assgnr>
      <Assgne>
        <Agt>
          <FinInstnId>
            <BICFI>{dest-bank-BICFI}</BICFI>
            <ClrSysMmbId><MmbId>{dest-bank-MmbId}</MmbId></ClrSysMmbId>
          </FinInstnId>
        </Agt>
      </Assgne>
    </Assgnmt>
    <Vrfctn>
      <Id>{same-as-MsgId}</Id>                          <!-- Mandatory -->
      <PtyAndAcctId>
        <Pty><Nm>{beneficiary-name}</Nm></Pty>          <!-- Mandatory -->
        <Acct>
          <Id><IBAN>{beneficiary-NUBAN}</IBAN></Id>     <!-- Mandatory: NUBAN here -->
        </Acct>
      </PtyAndAcctId>
    </Vrfctn>
  </IdVrfctnReq>
</ns2:Document>
```

---

## 7. Nigerian-Specific Fields Reference

### AccountDesignation Values

| Value | Meaning |
|-------|---------|
| 1 | Individual |
| 2 | Corporate |
| 3 | Government |

### AccountTier Values (CBN Tiered KYC)

| Tier | CBN KYC Level | Max Balance | Verification |
|------|--------------|-------------|-------------|
| 1 | BVN only | ₦300,000 | NONE/BASIC |
| 2 | BVN + address | ₦500,000 | STANDARD |
| 3 | Full KYC | Unlimited | ENHANCED |

### KYC Level → AccountTier Mapping (NubBank BaaS)

| NubBank `KycLevel` | NPS `AccountTier` |
|--------------------|--------------------|
| NONE | 1 |
| BASIC | 1 |
| STANDARD | 2 |
| ENHANCED | 3 |

### ChannelCode Values

| Code | Channel |
|------|---------|
| 1 | Internet Banking |
| 2 | Mobile Banking |
| 3 | USSD |
| 4 | POS |
| 5 | ATM |
| 6 | Branch/Teller |

### RiskRating Format
`R` + 19-character risk score + `B` + single digit (e.g., `R000000000000000000B9`)

---

## 8. NubBank BaaS Participation Requirements

| Requirement | Description | Who Provides | Status |
|-------------|-------------|-------------|--------|
| NIBSS Participant Registration | Register NubBank as NPS participant | NIBSS | Phase 2 |
| `MmbId` (CBN Sort Code) | NubBank's assigned member ID on NPS | NIBSS/CBN | Phase 2 |
| BICFI Code | Bank Identifier Code | NIBSS | Phase 2 |
| NPS Dedicated IP + Port | NIBSS assigns on registration | NIBSS | Phase 2 |
| RSA Key Pair (2048-bit+) | For XMLDSig signing of outgoing messages | NubBank generates | Phase 2 |
| NPS Encryption Certificate | NPS public key for encrypting messages | NIBSS provides | Phase 2 |
| NubBank Signing Certificate | NubBank public key for NPS to verify | NubBank provides to NIBSS | Phase 2 |
| BVN on all account holders | Customer.bvn_encrypted | Already in Phase 1A | ✅ |

---

## 9. Java Implementation Requirements

### Dependencies for Phase 2

```xml
<!-- Apache Santuario — XMLDSig + XMLEnc -->
<dependency>
    <groupId>org.apache.santuario</groupId>
    <artifactId>xmlsec</artifactId>
    <version>4.0.3</version>
</dependency>

<!-- JAXB for ISO 20022 XML binding -->
<dependency>
    <groupId>jakarta.xml.bind</groupId>
    <artifactId>jakarta.xml.bind-api</artifactId>
</dependency>
<dependency>
    <groupId>com.sun.xml.bind</groupId>
    <artifactId>jaxb-impl</artifactId>
</dependency>
```

### Key Java Classes (Phase 1B — stubs; Phase 2 — real implementations)

| Class | Phase 1B | Phase 2 |
|-------|----------|---------|
| `Pacs008Message` | Java POJO matching XML structure | Same |
| `Acmt023Message` | Java POJO matching XML structure | Same |
| `Acmt024Message` | Java POJO for parsing NPS response | Same |
| `Pacs002Message` | Java POJO for parsing NPS response | Same |
| `NpsXmlBuilder` | Builds XML string from Java POJO | Same |
| `NpsMessageSigner` | Stub — logs "would sign" | Real Apache Santuario RSA-SHA256 |
| `NpsMessageEncryptor` | Stub — returns plaintext XML | Real AES-256-GCM + RSA-OAEP |
| `NpsHttpClient` | Stub — returns mock acmt.024/pacs.002 | Real HTTPS to NIBSS NPS endpoint |
| `NipPaymentOrchestrator` | Full two-step flow (acmt.023 → pacs.008) | Same, with real HTTP |

---

## 10. Compliance Assessment

### Does NubBank BaaS Phase 1B Meet NPS Requirements?

| NPS Requirement | Phase 1B Design | Phase 2 Action Needed |
|----------------|-----------------|----------------------|
| ISO 20022 XML format for all NPS messages | ✅ Java models + NpsXmlBuilder | — |
| RSA-SHA256 digital signature | ✅ NpsMessageSigner interface (stub) | Replace with Apache Santuario + cert |
| AES-256-GCM + RSA-OAEP encryption | ✅ NpsMessageEncryptor interface (stub) | Replace with real keys |
| acmt.023 mandatory before pacs.008 | ✅ NipPaymentOrchestrator enforces two-step | Live NIBSS calls |
| BVN in SplmtryData — debtor | ✅ Customer.bvn_encrypted | Decrypt + include |
| BVN in SplmtryData — creditor | ✅ Extracted from acmt.024 mock | Live acmt.024 |
| NameEnquiryMsgId from acmt.024 | ✅ Orchestrator chains responses | Live |
| AccountTier mapping (KYC → tier) | ✅ KycLevel enum → tier (see mapping table) | — |
| NIBSS MmbId / participant registration | ❌ Config placeholder | NIBSS onboarding |
| Dedicated NPS IP endpoint | ❌ Config placeholder | NIBSS onboarding |
| XML must be signed + encrypted before send | ✅ Interfaces defined, real impl in Phase 2 | Apache Santuario |
| pacs.008.001.12 schema version | ✅ XML namespace correct in models | — |
| acmt.023.001.04 schema version | ✅ XML namespace correct in models | — |

**Conclusion:** Architecture is correctly designed for full NPS compliance. Phase 1B builds the complete pipeline with stub implementations. Phase 2 activates real NIBSS connectivity by replacing stubs with Apache Santuario implementations and configuring NIBSS credentials. **Zero architectural changes needed at Phase 2.**

---

## 11. Future Message Types (Roadmap)

NIBSS has indicated NPS is an evolving platform. Based on the current v1.2 message set, the following capabilities are likely to be added:

| Anticipated Addition | ISO 20022 Type | NubBank Relevance |
|---------------------|---------------|-------------------|
| Reversal | `pacs.007` | Allow payment reversals via NPS |
| Cancellation request | `camt.056` | Request cancellation of pending payment |
| Resolution of investigation | `camt.029` | Payment dispute resolution |
| Exception/admin messages | `admi.*` | System-level communication with NPS |

The `NpsMessageSigner` and `NpsHttpClient` interfaces are designed generically — any new message type can be added by implementing a new Java model class and a builder method, without changing the signing/encryption/HTTP infrastructure.

---

## 12. Reference Links

| Resource | URL |
|----------|-----|
| NPS Integration Guide | https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main/at4heqfolm224-welcome-page |
| NPS Message Types Overview | https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main/lczifj6ooqyit-nps-message-types-overview |
| NPS API Reference | https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main/cbfqgfoc5mvri-national-payment-stack-nps |
| pacs.008 Spec | https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main/f4c9nj0aeuta5-pacs-008-message |
| acmt.023 Spec | https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main/1nswab2mb4wcx-acmt-023-message |
| NPS Field Mapping | https://nibss.stoplight.io/docs/national-payment-stack-nps/branches/main/hp9kp30fsfkdk-nps-field-mapping |
| CBN Open Banking Guidelines (2023) | `docs/regulatory/CBN-Open-Banking-Operational-Guidelines-2023.md` |
| CBN Compliance Gap Analysis | `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md` |
