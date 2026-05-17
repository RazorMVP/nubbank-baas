# NubBank BaaS — CBN Open Banking Compliance Gap Analysis

**Reference:** CBN Operational Guidelines for Open Banking in Nigeria (Approved March 2023)
**Document:** `docs/regulatory/CBN-Open-Banking-Operational-Guidelines-2023.md`
**Last Updated:** Session 7+1 audit — 2026-05-17
**Assessed Against:** Phase 1A-ext + 1F-0 (cross-cutting security) + 1F-E (infrastructure hardening). HEAD: `fe00832`. Tests: 97 engine + 49 ncube — all green.

---

## Compliance Status Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Compliant — implemented and verified |
| ⚠️ | Partial — design-aware, full implementation pending |
| ❌ | Gap — not yet addressed, required for CBN certification |
| 📋 | Planned — scheduled for a specific Phase |

---

## 1. Interface Requirements (Section 8.6)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| REST architectural style | ✅ | Spring Boot REST throughout | Phase 1A |
| JSON data interchange format | ✅ | Jackson + ApiResponse envelope. CBN vendor media type `application/vnd.cbn.openbanking.v1+json` enforced on POST/PUT (Session 5, `OpenBankingMediaType`) | Phase 1A |
| ISO 20022 financial data standard | ⚠️ | pacs.008.001.12 and acmt.023.001.04 XML models built (Phase 1B); live NPS routing deferred to Phase 2 | Phase 2 live activation |
| SSL/HTTPS for all connections | ✅ | Enforced at infrastructure level (Vercel + K8s ingress TLS) | Phase 1E |
| OAuth 2.0 authentication | ✅ | Keycloak FAPI 2.0 for Open Banking flows | Phase 1A |

---

## 2. Open Banking Registry — OBR (Section 6)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| Register all participants in CBN OBR | ❌ | NubBank must register as API Provider; each partner as API Consumer | Phase 2 |
| CAC registration number as unique partner key | ❌ | `PartnerOrganization` has no `cacRegistrationNumber` field | Phase 2 |
| OBR API interface for managing AC registrations | ❌ | Requires integration with CBN OBR API | Phase 2 |
| Public repository of registered participants | ❌ | CBN-hosted; NubBank registers via OBR API | Phase 2 |

**Impact:** NubBank BaaS cannot go live in the Nigerian market without OBR registration. This must be addressed in Phase 2 alongside Ncube integration.

**Required changes to `PartnerOrganization` entity:**
```
+ cac_registration_number  VARCHAR(20) UNIQUE  -- CAC RC number
+ obr_registration_id      VARCHAR(100)         -- OBR-issued ID
+ obr_status               VARCHAR(50)          -- PENDING | REGISTERED | SUSPENDED
```

---

## 3. Consent Management (Section 7 + Appendix 1, Section 3.1–3.2)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| Explicit customer consent required | ✅ | Consent entity with AWAITING_AUTHORISATION → AUTHORISED → REVOKED | Phase 1A |
| Consent must include AC full legal name | ⚠️ | Consent stores orgId; name lookup needed in consent display | Phase 2 |
| Consent must include AC CAC number | ❌ | CAC field not yet on PartnerOrganization | Phase 2 |
| Consent must include type of access | ✅ | Scopes stored on consent | Phase 1A |
| Consent must include duration/expiry | ✅ | `expiryDate` on consent | Phase 1A |
| Consent must include access frequency | ⚠️ | Not yet captured explicitly | Phase 2 |
| Customer can opt-out at any time | ✅ | DELETE /baas/v1/open-banking/consents/{id} | Phase 1A |
| Consent re-validated annually | ❌ | No annual re-validation scheduler | Phase 3 |
| Consent re-validated if AC inactive 180 days | ❌ | No 180-day inactivity check | Phase 3 |
| Connection terminates on consent expiry | ✅ | ConsentService validates expiry before any AISP/PISP call | Phase 1A |
| Two-Factor Authentication for consent verification | ⚠️ | 2FA module built in SaaS; needs wiring into BaaS consent flow | Phase 2 |
| Consent records pushed to CBN/Ncube registry | ❌ | baas-ncube will handle this | Phase 2 |

---

## 4. Authentication and Security (Appendix III + Section 5)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| OAuth 2.0 + OpenID Connect (minimum) | ✅ | Keycloak FAPI 2.0. `AuthEnforcementFilter` (Session 5) provides default-deny on `/baas/v1/**` so new endpoints are protected by default rather than relying on per-service `requireContext()` discipline. | Phase 1A |
| FAPI (Financial-grade API) specifications | ✅ | Keycloak FAPI 2.0 profile configured | Phase 1A |
| Multi-Factor Authentication (MFA) | ⚠️ | Keycloak MFA enabled; not enforced end-to-end in BaaS flow | Phase 2 |
| Mutual TLS (mTLS) for machine-to-machine | ❌ | Partner → engine: API key (SHA-256) + Partner JWT (HMAC). Engine ↔ ncube: body-signed HMAC-SHA256 (`InternalServiceAuthFilter`, Session 5 — closes the inter-service auth gap differently). True cert-based mTLS still required by CBN — pending Phase 3. | Phase 3 |
| JWT token format and expiry | ✅ | Partner JWT: HMAC-SHA256, 24h expiry | Phase 1A |
| Asymmetric JWT keys (RSA/EC — JWS RFC 7515) | ❌ | Currently HMAC-SHA256; CBN requires asymmetric keys for non-repudiation | Phase 2 |
| JSON Web Encryption (JWE RFC 7516) | ❌ | Message-level encryption not yet implemented | Phase 3 |
| JSON Web Signature (JWS RFC 7515) | ❌ | Message signing not yet implemented | Phase 2 |
| TLS 1.2 minimum (mutual auth RFC 8705) | ⚠️ | TLS enforced at infra level; mutual auth not yet | Phase 3 |
| TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 cipher | ⚠️ | Infra-level TLS config needed | Phase 1E |
| Zero-trust architecture | ⚠️ | Data-level zero-trust via Hibernate SCHEMA isolation (Phase 1A). Network-level zero-trust via Kustomize `network-policy` component (Phase 1F-E, Session 6) — pod-to-pod ingress/egress restrictions. Identity-level zero-trust (mTLS, per-request authZ) still pending. | Phase 3 |
| Role-based access control (RBAC) | ✅ | Partner roles: PARTNER_ADMIN, TELLER, LOAN_OFFICER, COMPLIANCE, FINANCE | Phase 1A |

---

## 5. Customer Data Standards (Appendix 1, Section 3.3)

| CBN Required Field | Status | Field in Customer Entity | Notes |
|-------------------|--------|------------------------|-------|
| First Name | ✅ | `first_name_encrypted` | **Encrypted at rest (AES-GCM-256, Session 4)** |
| Middle Name | ❌ | Missing | Add `middle_name_encrypted` (Phase 2) |
| Last Name | ✅ | `last_name_encrypted` | **Encrypted at rest (AES-GCM-256, Session 4)** |
| Contact Address | ✅ | `client_addresses` | Built Session 3; **street/city/postal_code encrypted at rest (Session 4)** |
| Date of Birth | ✅ | `date_of_birth` | Present |
| State of Residence | ❌ | Missing | Add `state_of_residence` field (Phase 2) |
| NIN | ✅ | `nin_encrypted` | **Encrypted at rest (Session 4)**; live verification Phase 2 |
| BVN | ✅ | `bvn_encrypted` | **Encrypted at rest (Session 4)**; live verification Phase 2 |
| Email Address | ✅ | `email_encrypted` | **Encrypted at rest (Session 4)** |
| Phone Number | ✅ | `phone_encrypted` | **Encrypted at rest (Session 4)** |
| Identity documents (passport / DL / SSN) | ✅ | `client_identifiers.document_key` | **Encrypted at rest (Session 4)** |

**Account Data Fields:**

| CBN Required Field | Status | Notes |
|-------------------|--------|-------|
| Account Number | ✅ | NUBAN format |
| Currency | ✅ | `currency_code` |
| Status | ✅ | AccountStatus enum |
| Created Date | ✅ | `created_at` |
| Account Name | ✅ | `account_name` |
| Account Type | ✅ | `account_type_label` |
| Balance | ✅ | `balance` (real-time) |
| BVN | ⚠️ | Linked via Customer entity |
| Bank Name | ⚠️ | NubBank BaaS — partner branding in Phase 3 |

---

## 6. KPI and Performance Requirements (Section 8.3)

| CBN KPI | Required Standard | Our Target | Status |
|---------|------------------|-----------|--------|
| API Availability | >98% (Operational) | 99.9% SLA | ✅ |
| Avg. API Total Processing Time | <3 seconds | <500ms | ✅ |
| Success Rate | >95% | Target >99% | ✅ |
| Incident Response — Functional | 2 hours | — | ⚠️ Define SLA |
| Incident Response — Performance | 30 minutes | — | ⚠️ Define SLA |
| Incident Response — Systemic | 15 minutes | — | ⚠️ Define SLA |

**12 Specific CBN Metric Requirements (Section 8.2.3):**

| # | CBN Metric | Status | Notes |
|---|-----------|--------|-------|
| 1 | `msg_validation_time` | ❌ | Not captured in billing_events |
| 2 | `network_proc_time` | ❌ | Not captured |
| 3 | `avg_db_time` | ❌ | Not captured |
| 4 | `avg_ext_call_time` | ❌ | Not captured |
| 5 | `avg_log_time` | ❌ | Not captured |
| 6 | `avg_total_proc_time` | ❌ | Not captured |
| 7 | `avg_req_proc_time` | ❌ | Not captured |
| 8 | `avg_rsp_proc_time` | ❌ | Not captured |
| 9 | `total_api_calls` | ✅ | billing_events count per partner |
| 10 | `%_success` | ⚠️ | HTTP status codes logged but not aggregated |
| 11 | `%_approved` | ⚠️ | Not yet distinguished from %_success |
| 12 | `calls_per_sec` | ❌ | Not captured |

**Plan:** Add a `PerformanceMetricsInterceptor` (Spring `HandlerInterceptor`) in Phase 2 that captures timing metrics and persists them to a `api_performance_log` table.

---

## 7. Event Logging and Data Retention (Section 8.2.4 + Section 6.0 Appendix III)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| Requests/responses logged for 180+ days | ✅ | Audit log with 10-year retention (append-only) | Phase 1A |
| Tamper-proof digital logs | ⚠️ | Append-only table; cryptographic signing not yet implemented | Phase 3 |
| Open banking data retained minimum 10 years | ✅ | Audit log policy: 10 years | Phase 1A |

---

## 8. AML/CFT Requirements (Section 9.5)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| KYC/CDD policy | ⚠️ | KYC levels (NONE/BASIC/STANDARD/ENHANCED) defined; policy framework pending | Phase 2 |
| AML/CFT due diligence | ⚠️ | Fraud rules exist; dedicated AML transaction monitoring pending | Phase 3 |
| Sanctions screening (OFAC/local) | ❌ | Platform integrity rules table defined; population pending | Phase 2 |
| Suspicious activity reporting | ❌ | Not yet implemented | Phase 3 |

---

## 9. Data Privacy — NDPR Compliance (Section 9.2)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| Comply with NDPR (Nigeria Data Protection Regulation) | ✅ | **PII field-level encryption ACTIVE (AES-GCM-256, Session 4)** — Customer name/email/phone/BVN/NIN; ClientIdentifier document_key; ClientAddress street/city/postal_code. Verified via `PiiEncryptionTest` — row on disk is ciphertext. **PII log masking ACTIVE (Logback `PiiMaskingConverter`, Session 5)** — context-anchored regex masks PAN, BVN, NIN, NUBAN in application logs; deliberately scoped out of MDC values, structured args, and exception messages to avoid false positives on trace IDs / Unix timestamps. | Phase 1 ✅ |
| Data retention and destruction policy | ⚠️ | 10-year retention defined; destruction policy documentation pending | Phase 3 |
| Customer right to data portability | ❌ | Data export API not yet implemented | Phase 3 |
| Data loss prevention (DLP) | ✅ | Two-layer DLP active: (1) at-rest field-level encryption AES-GCM-256 (Session 4) and (2) Logback `PiiMaskingConverter` masking PAN/BVN/NIN/NUBAN in production logs (Session 5). Egress DLP (e.g. content-aware proxies) remains a Phase 3+ topic but is not a CBN baseline requirement. | Phase 1 ✅ |

---

## 10. Reporting Requirements (Section 8.8 + Section 9.4)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| Monthly API performance reports to partners | ❌ | Report Mailing Jobs planned; not yet built in BaaS | Phase 3 |
| Monthly report to CBN via OBR API | ❌ | Requires OBR integration | Phase 2 |
| Incident/problem reports | ⚠️ | Audit log exists; formatted CBN reports pending | Phase 3 |
| Transaction volume/value/success/failure to CBN | ❌ | Billing events captured but CBN-format report not built | Phase 2 |

---

## 11. Consent Management API Operations (Appendix 1, Section 4.0)

| CBN API Operation | Status | Our Endpoint | Notes |
|------------------|--------|-------------|-------|
| Request Consent | ✅ | `POST /baas/v1/open-banking/consents` | Implemented |
| Retrieve Consent | ✅ | `GET /baas/v1/open-banking/consents/{id}` | Implemented |
| Cancel/Revoke Consent | ✅ | `DELETE /baas/v1/open-banking/consents/{id}` | Implemented |
| Create Debit Mandate | ❌ | Not yet implemented | Phase 2 (PISP enhancement) |
| Retrieve Mandate | ❌ | Not yet implemented | Phase 2 |
| Delete Mandate | ❌ | Not yet implemented | Phase 2 |

---

## 12. Business Continuity (Section 8.4 + 9.7)

| CBN Requirement | Status | Notes | Planned Phase |
|----------------|--------|-------|--------------|
| BCP with quarterly failover exercises | ⚠️ | Infrastructure prerequisites in place: HPA tuning + Pod Disruption Budgets component (Phase 1F-E, Session 6) + NetworkPolicy component. BCP documentation and quarterly exercise schedule still owed. | Phase 1E |
| 30-minute failover threshold | ⚠️ | HPA + Pod Disruption Budgets tuned (Phase 1F-E, Session 6) improve graceful degradation under single-pod loss. Multi-AZ K8s deployment still planned, not yet provisioned. | Phase 1E |
| Disaster Recovery Plan | ❌ | PostgreSQL backup strategy not yet documented | Phase 1E |
| BCP/DRP tested every 6 months | ❌ | Policy not yet formalized | Phase 4 |

---

## Summary Scorecard

| Category | Compliant ✅ | Partial ⚠️ | Gap ❌ | Priority |
|----------|------------|-----------|-------|---------|
| Interface Requirements | 4 | 1 | 0 | ✅ |
| Open Banking Registry | 0 | 0 | 4 | 🔴 Critical — Phase 2 |
| Consent Management | 6 | 3 | 3 | 🟡 Phase 2–3 |
| Authentication & Security | 5 | 3 | 5 | 🟡 Phase 2–3 |
| Customer Data Standards | 8 | 2 | 2 | 🟡 Phase 2 |
| KPI & Performance | 3 | 3 | 6 | 🟡 Phase 2 |
| Event Logging & Retention | 2 | 1 | 0 | ✅ |
| AML/CFT | 0 | 2 | 2 | 🟡 Phase 2–3 |
| Data Privacy (NDPR) | 2 | 1 | 1 | 🟡 Phase 3 (at-rest encryption ✅ Session 4, log-time PII masking ✅ Session 5; portability + destruction policy pending) |
| Reporting | 0 | 1 | 3 | 🟡 Phase 2–3 |
| Consent API Operations | 3 | 0 | 3 | 🟡 Phase 2 |
| Business Continuity | 0 | 2 | 2 | 🟡 Phase 1E (HPA + PDB + NetworkPolicy ✅ Session 6; BCP/DRP docs + Multi-AZ deploy + 6-month exercise cycle still owed) |

---

## Critical Path to CBN Certification

The following items are **blockers** for operating legally in the Nigerian open banking ecosystem:

1. **OBR Registration** (Section 6) — NubBank must register as an API Provider; partners register as API Consumers via NubBank's OBR interface. Requires `cac_registration_number` on `PartnerOrganization`.

2. **Asymmetric JWT Keys** (Appendix III, Section 10) — HMAC-SHA256 is not CBN-compliant for non-repudiation. Must migrate to RSA or EC keys (JWS RFC 7515).

3. **BVN/NIN Live Verification** (Section 3.3.1, Appendix III, Section 4.iii) — BVN/NIN fields must be verified against NIBSS Ncube identity rails before any account opening.

4. **Ncube Consent Registry Sync** (Section 7, Section 11.1) — All consent grants must be pushed to the CBN national consent registry via Ncube.

5. **Customer Middle Name + State of Residence** (Section 3.3.1) — Required CBN data fields not yet in Customer entity.

6. **CAC Number on PartnerOrganization** (Section 6) — OBR uses CAC registration number as the unique participant key.

All six items are targeted for **Phase 2 (Model A — Fintech/Neobank)** which is the first phase where real partners go live.
