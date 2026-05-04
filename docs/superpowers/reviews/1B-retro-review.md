# Phase 1B Retrospective Code Review

**Reviewed**: 2026-05-03
**Scope**: PR #1, commit `a3bf0c7` (frozen snapshot — `baas-ncube/` only)
**Files reviewed**: 56 files / ~2,009 LOC (37 Java prod, 6 Java test, application.yml, pom.xml)
**Reviewer**: superpowers:code-reviewer (retrospective, post-merge)
**Plan**: `docs/superpowers/plans/2026-04-27-nubbank-baas-phase1b-ncube.md`

---

## Summary

Phase 1B delivered a clean, well-structured stub-driven adapter with sensible interface boundaries (`NpsMessageSigner` / `NpsMessageEncryptor` / `NpsHttpClient`) that will let Phase 2 swap in real NIBSS connectivity without architectural surgery. The `@ConditionalOnProperty(matchIfMissing=true)` pattern on the stub beans is the right choice. However, the merge introduced **multiple critical safety gaps** that should have been blocked by review: stubbed BVN/NIN verification returns `verified=true` indistinguishably from a real response, the service is fully `permitAll()` with no inter-service authentication, the stub HTTP client ignores the request entirely (so payments to non-existent destination accounts always succeed), there is no `EndToEndId` idempotency, and structured PII (BVN, NIN, account numbers) is logged at DEBUG with full digits. None of this is acceptable to ship to a CBN-regulated environment without remediation, and Phase 2 must not begin until at least the Critical findings are resolved.

---

## Critical findings

### C1. Stubbed BVN/NIN verification is structurally indistinguishable from a real one — onboarding-fraud risk
**File:** `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java:30-38, 42-50`

The `/verify-bvn` and `/verify-nin` endpoints unconditionally return `verified=true` for any 11-digit input. The only "stub" indicator is the string `"NIBSS_NCUBE_STUB"` in the `verificationSource` field. There is **no HTTP header**, **no response envelope flag** (`stubbed: true`), **no warning log at WARN/ERROR level** on every call, and **no startup banner** that would force operators to notice a stubbed deployment. Worse: the log line is at `DEBUG`, which is off by default given `application.yml` sets `com.nubbank.baas.ncube: INFO`. So in any default-config production deployment, a stubbed BVN response that reads `verified=true` flows silently to `baas-engine` — and if `baas-engine` trusts that flag, an attacker can onboard with **any** 11-digit string and become a verified customer.

**Scenario:** ops mis-deploys `NPS_LIVE=false` (or never sets it; `matchIfMissing=true` means stub is the default), the service starts, and partners' onboarding traffic begins. BVN `00000000001` returns "verified, source: NIBSS_NCUBE_STUB". Downstream `baas-engine` has no way to know this is fake unless it special-cases the `verificationSource` string — which is fragile.

**Fix:**
1. Add a startup banner: when `baas.nps.live=false`, emit `log.warn("STUB MODE: NIBSS calls are mocked. DO NOT deploy with this configuration to production.")` from `BaasNcubeApplication` or a `@PostConstruct` hook.
2. Add an HTTP response header on every stubbed response: `X-NubBank-Stubbed: true` with the property/feature name.
3. Refuse to start in stub mode if `SPRING_PROFILES_ACTIVE` contains `prod` (fail-loud guardrail).
4. Use `00000000000` (eleven zeros) for stubbed identifiers instead of echoing the caller's input — a real BVN/NIN registry would never return a 0-string. This makes downstream "is this real?" detection deterministic.

### C2. `SecurityConfig` is `permitAll()` with no inter-service authentication — anyone on the network is the service
**File:** `baas-ncube/src/main/java/com/nubbank/baas/ncube/config/SecurityConfig.java:13-21`

The configuration sets `auth.anyRequest().permitAll()` and the comment justifies this with "baas-ncube does not validate auth — it forwards Authorization header to baas-engine on every call." This conflates two distinct concerns:

1. **End-user / partner authorization** — yes, fine to delegate to `baas-engine`.
2. **Inter-service authentication** — i.e., proving that the caller hitting `baas-ncube:8082` is `baas-engine` (or an authorized partner gateway), not an attacker who reached the pod over a flat cluster network.

There is no mTLS, no shared secret check, no IP allowlist, no service mesh assumption documented. The identity verification endpoints (`/verify-bvn`, `/verify-nin`) don't even *use* the Authorization header — they accept it as `required = false` and ignore it. Anyone with network reachability to port 8082 can call BVN verification with no credentials at all. In Phase 1B (stub) this is "only" a recon vector; in Phase 2 (live NIBSS), it becomes "anyone on the cluster can drain our NIBSS API quota and trigger NIP fraud probes".

**Fix:** Before Phase 2, require either (a) mTLS between `baas-engine` and `baas-ncube` enforced at the controller layer, or (b) a shared signed token (HS256 of body+timestamp) that `baas-engine` mints and `baas-ncube` validates. Document the assumed network posture in `application.yml` next to `baas.engine.base-url`.

### C3. Stub `NpsHttpClient` ignores the request entirely — every payment to any destination "succeeds"
**File:** `baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/StubNpsHttpClient.java:21-43, 47-58`

`StubNpsHttpClient.sendAcmt023(...)` and `sendPacs008(...)` take a `signedEncryptedXml` argument but never read or echo it. Both return hard-coded XML in which the beneficiary is always `"Stub Beneficiary"` with account `"0581000099"`, BVN `"98765432109"`, and the payment status is **always** `ACSC` (Accepted Settlement Completed). The orchestrator (`NipPaymentOrchestrator.java:62-66`) parses this and treats it as success. So during 1B:

- Posting a payment to destination account `0000000000` returns `COMPLETED`.
- Posting a payment with a non-existent `destinationBankCode` returns `COMPLETED`.
- Posting a payment with `amount=999999999999.00` returns `COMPLETED`.
- The stubbed acmt.024 even returns a different `IBAN` (`0581000099`) than the request's `destinationAccountNumber` — and `NipPaymentOrchestrator` then constructs `pacs.008` with `acmt024.beneficiaryName()` (`"Stub Beneficiary"`), silently overwriting the partner's intended beneficiary.

**Scenario:** during integration testing or a misconfigured prod, a partner sends 1,000 NIP requests to invalid account numbers and gets 1,000 `COMPLETED` responses back. They reconcile against their ledger, see "all paid", credit their merchants — and discover days later that nothing actually moved on NIBSS.

**Fix:** Stubs should at minimum (a) parse the request XML, (b) echo the requested beneficiary back, (c) deterministically reject obviously-bad inputs (account != 10 digits, amount > some sandbox cap), and (d) reject some percentage of requests with `RJCT` so partners exercise the failure path. The current "always succeed" stub is worse than no stub at all because it hides bugs.

### C4. No `EndToEndId` idempotency — duplicate-payment risk on retry
**File:** `baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/NipPaymentOrchestrator.java:31-99` (entire `initiate` method)

NIP requires that the same `EndToEndId` from the partner produce **the same result** on retry. The orchestrator generates a fresh `e2eRef = uid()` on every call (line 32), regardless of any client-supplied identifier. There is no `Idempotency-Key` header, no de-duplication store (Redis / DB), no read-through cache. If `baas-engine` retries due to a network timeout (which is normal), `baas-ncube` will:

1. Send a fresh `acmt.023` (new `MsgId`), get a fresh stub `acmt.024`.
2. Send a fresh `pacs.008` (new `EndToEndId`, new `TxId`, new `InstrId`), and in Phase 2 NIBSS will **debit the source account a second time**.

In Phase 1B this is silent; in Phase 2 it's a duplicated debit per retry. The plan explicitly mentions this requirement (NPS Analysis doc) but the implementation does not enforce it.

**Fix:** Accept an optional `Idempotency-Key` (or use `req.endToEndId` as a required field). Persist a (key → result) mapping in Redis with a 24-72h TTL. On every `initiate()`, check first; if seen, return the original result. This is non-trivial — better to add it now during stub phase than after live NIBSS retries cause duplicated debits.

### C5. Sensitive data — BVN, NIN, account numbers — logged in cleartext
**Files:**
- `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java:32` — `log.debug("BVN verification: {} ...", req.bvn())`
- `baas-ncube/src/main/java/com/nubbank/baas/ncube/identity/NcubeIdentityController.java:46` — `log.debug("NIN verification: {} ...", req.nin())`
- `baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/NipPaymentOrchestrator.java:38` — `log.info("NIP initiated: id={} dest={} amount={}", paymentId, req.destinationAccountNumber(), req.amount())`

Although BVN/NIN logging is at `DEBUG` and the default level is `INFO` (so off in default deployments), there is no enforcement preventing an SRE from flipping log level to `DEBUG` to investigate an issue and accidentally exposing PII to the logging pipeline (CloudWatch / Datadog / ELK), where it will be retained for the log retention period. The NIP `dest` and `amount` fields are at `INFO` and **will** be logged in production.

CBN's data protection guidelines (and NDPR/NDPA) treat BVN, NIN, and account numbers as personal data; logging them in cleartext is a regulator-finding-class issue at peer fintechs.

**Fix:**
1. Wrap BVN/NIN in a masking helper: `bvn.substring(0,3) + "*****" + bvn.substring(8)` (or last 4 only).
2. Apply the same to `destinationAccountNumber` (`"058100****"`).
3. Add a Logback/Spring filter that pattern-matches 11-digit-strings adjacent to `bvn=` / `nin=` and rejects the line at any level.
4. Document the log policy in `application.yml`.

### C6. ISO 20022 hand-rolled string-template XML — multiple wire-format defects
**File:** `baas-ncube/src/main/java/com/nubbank/baas/ncube/payment/nps/NpsXmlBuilder.java:27-104`

The XML is built by string concatenation. This carries several risks that an XSD-validated builder (JAXB or schemagen) would have caught:

1. **`MsgId` reuse** — `buildAcmt023` writes `msg.msgId()` into both `<Assgnmt><MsgId>` and `<Vrfctn><Id>`. ISO 20022 specifies these are independent identifiers; reuse may pass NPS schema validation but will collide with NIBSS reconciliation tooling that keys off `Vrfctn/Id` separately.
2. **`CreDtTm` format** — `Instant.now().toString()` produces `2026-05-03T09:14:23.123456789Z` (nanosecond precision). ISO 20022 `ISODateTime` type requires `YYYY-MM-DDTHH:MM:SS[.sss]Z` with **at most 3 fractional-second digits**. NIBSS schema validation will reject nanosecond-precision timestamps. Use `DateTimeFormatter.ISO_OFFSET_DATE_TIME` with `withNano(...)` truncation.
3. **`InstgAgt` BICFI hardcoded to `memberId`** — `NpsXmlBuilder.java:73` writes `esc(memberId)` into `<BICFI>`. BICFI is an ISO 9362 BIC (8 or 11 chars, alphabetic prefix) but `memberId` is the NIBSS member code (`999058`). This is wrong; should be `memberIdBicfi` (which is also `999058` in default config — also wrong but at least documented).
4. **No XSD validation step** — there is no `validateAgainstSchema()` call before sending. In Phase 2 every malformed message will round-trip to NIBSS and bounce, increasing latency and hammering the NPS rate limit.
5. **`BtchBookg`, `NbOfTxs`, `SttlmMtd`, `ChrgBr`, `ClrChanl`, `SvcLvl`, `LclInstrm`, `CtgyPurp` are all hard-coded literals** (`false`, `1`, `CLRG`, `SLEV`, `RTNS`, `0100`, `CTAA`, `001`). Some of these may need to differ per transaction type (e.g. `CtgyPurp` for salary vs. P2P); a hard-coded `001` will cause NIBSS to misclassify NIP traffic.
6. **`SplmtryData/CustomData` content is ad-hoc XML** — e.g. `<DebtorInfo><AccountDesignation>...</AccountDesignation>` — these tags are not standard ISO 20022; they are NIBSS-proprietary. They should be wrapped in a defined namespace (`xmlns:nps="..."`) so NIBSS can validate them against their proprietary XSD.

**Fix:** Replace the string-template builder with **JAXB classes generated from the actual NPS XSDs** (Apache CXF wsdl2java or `xjc`). This is the same code that Phase 2 will need anyway; doing it now means Phase 2 is only "swap the stub HttpClient" and the wire format is already correct. The `NpsXmlBuilderTest` should validate against the XSD, not against a string match.

---

## Important findings

### I1. `permitAll()` exposes `/actuator/**` to the world
**File:** `application.yml:21-25` exposes `health,info`; `SecurityConfig.java:18` permits all. Anyone reachable on port 8082 can `GET /actuator/info`. Not catastrophic, but `info` can leak `git.commit.id`, build version, and active profile — useful for an attacker. Fix: restrict actuator to a separate management port or require auth on `/actuator/**`.

### I2. Health check returns UP regardless of NIBSS connectivity
**File:** `application.yml:21-25` (`exposure: health,info`). There is no custom `HealthIndicator` for NPS reachability. In Phase 2 the service will report UP even if NIBSS is unreachable — auto-scalers and partners' status pages will be misled. Add `NpsHealthIndicator` now (against the stub it can return UP-with-comment-`STUB`); Phase 2 then upgrades it without an architectural change.

### I3. CBN content-type strictness — service silently accepts any JSON
**Files:** `NcubeAccountController.java:14-46`, `NcubeConsentController.java:13-46`, `NcubePaymentController.java:11-25`. None of the controllers declare `consumes = "application/vnd.cbn.openbanking.v1+json"` or `produces = "application/vnd.cbn.openbanking.v1+json"`. The plan describes this as a CBN format adapter but the implementation accepts plain `application/json` and produces plain `application/json`. CBN auditors do check the wire format. Fix: add the vendor media type on every controller and reject 415 on mismatch.

### I4. `NcubeAccountClient.getBalance` does an O(n) scan of all accounts
**File:** `baas-ncube/src/main/java/com/nubbank/baas/ncube/account/NcubeAccountClient.java:34-40`. Calling `GET /balances/{nuban}` calls `getAccounts(authHeader)` which fetches up to 100 accounts and filters in memory. For a partner with >100 accounts the requested NUBAN may not be in the first page and will return 404 even when present. `getTransactions` has the same flaw. Fix: add `GET /baas/v1/accounts/by-number/{nuban}` to `baas-engine` and call it directly.

### I5. `Map`-typed RestTemplate responses with raw types
**File:** `NcubeAccountClient.java:32, 50, 84, 88` and `NcubeConsentClient.java:36, 60`. Use of raw `Map` and `@SuppressWarnings("rawtypes")` defeats type safety. A schema change in `baas-engine` (e.g. renaming `accountNumber` to `nuban`) would silently produce empty strings without any test failure. Fix: introduce typed inner DTOs with `ParameterizedTypeReference<Map<String, NubBankAccountDto>>`.

### I6. Catching `Exception` then re-throwing as `NPS_ENQUIRY_ERROR` swallows root cause
**File:** `NipPaymentOrchestrator.java:53-56, 84-86`. The catch block uses `Exception ex` and constructs `new NcubeException(...)` without `cause = ex` (the `NcubeException` constructor has no cause-accepting overload). Stack traces are lost; debugging Phase 2 production incidents will be much harder. Fix: add a `Throwable cause` constructor to `NcubeException`; pass the original.

### I7. `parseAcmt024` "verified" heuristic is loose
**File:** `NpsXmlParser.java:30` — `boolean verified = "true".equalsIgnoreCase(vrfd) || !acctNm.isEmpty();`. The `||` arm means a response that has a name but NO `<Vrfd>` element at all will be treated as verified. NIBSS rules require `<Vrfd>true</Vrfd>` explicitly. In Phase 2, an NPS response of the wrong shape could pass through as "verified". Fix: require `vrfd.equalsIgnoreCase("true")` only.

### I8. `NpsXmlParser.text()` uses first-occurrence search across the whole document
**File:** `NpsXmlParser.java:53-57`. `getElementsByTagNameNS("*", "MsgId")` returns ALL `MsgId` nodes anywhere in the document and `text(...)` takes the first. In `acmt.024` there are two `MsgId` elements (one in `Assgnmt`, one in `OrgnlAssgnmt`). The parser will return whichever is listed first in the document order — fragile and tightly coupled to NIBSS's serialization. Fix: scope each lookup to its parent (`Assgnmt > MsgId` vs `OrgnlAssgnmt > MsgId`).

### I9. No XXE protections on the parser despite presence of one
**File:** `NpsXmlParser.java:48-51`. `disallow-doctype-decl` is set but `setExpandEntityReferences(false)`, `setXIncludeAware(false)`, `external-general-entities`, and `external-parameter-entities` features are not explicitly disabled. For a stub it's OK; in Phase 2 NIBSS responses must be treated as untrusted (they could be MITM'd if mTLS isn't perfectly configured). Add the full OWASP-recommended set.

### I10. `GlobalExceptionHandler` swallows stack traces with generic `INTERNAL_ERROR`
**File:** `GlobalExceptionHandler.java:30-35`. Logging `log.error("Unhandled exception", ex)` is good — but the response body is `{"error":"INTERNAL_ERROR","message":"An unexpected error occurred"}`. There is no correlation ID returned to the client to trace back to the log entry. Fix: generate a UUID in the handler, log it, and include it in the response body as `traceId`.

### I11. Only stubs implementing the NPS interfaces — no fail-loud guard if both stub and real impl absent
The interfaces `NpsHttpClient`, `NpsMessageSigner`, `NpsMessageEncryptor` are wired into `NipPaymentOrchestrator`'s constructor as required dependencies. If someone in Phase 2 sets `baas.nps.live=true` but doesn't add a `RealNpsHttpClient @ConditionalOnProperty(... havingValue="true")`, Spring will fail at startup with a missing-bean error — which is *technically* fail-loud, but a friendlier `@ConditionalOnMissingBean` fallback that throws "live mode requires production NPS client; consult Phase 2 docs" would be safer. Low priority but worth noting.

### I12. Smoke test summary in commit `a222e93` is the only evidence of a successful run
There is no committed integration test, no GitHub Actions CI workflow for `baas-ncube/`, and no recorded test output in `docs/`. The plan section 9 mentions "21 tests" — but absent CI, the only proof these tests passed is the one-line commit message. Add a CI workflow before merging Phase 2.

---

## Minor findings

- **m1.** `NipPaymentOrchestrator.java:101-103` — `uid()` uses `UUID.randomUUID().toString().replace("-","").substring(0,32)` which is exactly the UUID hex; the `.substring(0,32)` is a no-op (UUID-no-dashes is always 32 chars). Remove for clarity.
- **m2.** `NipPaymentOrchestrator.java:25-43` — three `@Value` strings stored as mutable fields (`institutionName`, `memberIdBicfi`, `memberId`) but no setters; should be `private final`.
- **m3.** `NpsXmlBuilder.java:91-103` — `buildSplmtryData` ignores `esc()` for `dbtrAccountDesignation`, `dbtrAccountTier`, `cdtrAccountDesignation`, `cdtrAccountTier` (they're `int`, so it's fine, but inconsistent with surrounding `esc()` calls — leaves room for future bugs if the type changes).
- **m4.** `NpsXmlBuilder.java:108-115` — `esc()` is correct but doesn't handle ` `-`` control characters which are illegal in XML 1.0. NIBSS will reject the message; better to strip these too.
- **m5.** `NipPaymentRequest.java:8` — `@Size(min = 10, max = 10)` for `destinationAccountNumber` enforces NUBAN length, good. But there's no `@Pattern("\\d{10}")` so `"abcdefghij"` would pass validation. Add the digit pattern.
- **m6.** `NipPaymentRequest.java:13-14` — `int debtorAccountTier` and `int debtorAccountDesignation` accept negative values and zero. Change to `@Min(1) @Max(3)` enums or annotate.
- **m7.** `NcubeAccountClient.java:79` — `decimal()` calls `new BigDecimal(String.valueOf(...))` which throws `NumberFormatException` if the value is not numeric. The exception bubbles up as 500 rather than a structured error. Wrap.
- **m8.** `application.yml:9-19` — `member-id` and `bicfi` default to `999058` (the same value). One is meant to be the NIBSS member ID, the other the BIC. Defaulting them equal is convenient but has masked real config errors during smoke tests.
- **m9.** `NcubeConsentController.java:42` — switch over status strings; if `baas-engine` adds a new status (e.g. `EXPIRED`), it falls through the default and is forwarded literally to the partner. Add an explicit log warning on default.
- **m10.** Plan says service runs on port 8081; `application.yml:6` sets `8082`. Plan also says the same in line 5 of the plan. Disagrees with the task description supplied to this review (which says 8081). Confirm and document.
- **m11.** No `dependencyManagement` lock on `springdoc-openapi-starter-webmvc-ui` version vs Spring Boot 3.5.0 — pinned to `2.8.6` is fine but the plan doesn't reference an SBOM. Add OWASP Dependency-Check to CI.
- **m12.** `BvnVerificationRequest` and `NinVerificationRequest` have identical structure — extract a base interface for future shared validation.
- **m13.** No `@Transactional` semantics anywhere — correct for a stateless adapter, but worth a one-line README note explaining "no DB; nothing to roll back".

---

## Strengths

- **Clean interface boundary for NPS internals.** `NpsHttpClient`, `NpsMessageSigner`, `NpsMessageEncryptor` are tiny, single-method interfaces with stub implementations gated by `@ConditionalOnProperty(matchIfMissing=true)`. Phase 2 swap is genuinely a one-line config change per impl. This is the design call I'd have wanted at the merge.
- **Adapter pattern keeps `baas-engine` ignorant of CBN/ISO 20022.** All format translation is local to `baas-ncube`. The single-responsibility split is clean.
- **DTO records and Lombok kept out of the wire-shape DTOs.** `CbnApiResponse<T>(T Data, CbnLinks Links, CbnMeta Meta)` is a Java record — no setters, no mutability bugs, JSON shape matches CBN field-name convention exactly.
- **DOM parser disables DOCTYPE.** `NpsXmlParser.java:50` sets `disallow-doctype-decl=true` — protects against billion-laughs and basic XXE. Should be hardened further (I9) but the start is right.
- **Stub responses ARE returned even when `baas.nps.live` is unset** — `matchIfMissing=true` makes the safe choice the default. A new dev cloning the repo can run the service immediately.
- **Plan-to-code traceability is high.** Most files match the plan section; comment headers cite the plan's regulatory references (`docs/regulatory/NIBSS-NPS-ISO20022-Analysis.md`). Easy to trace decisions.

---

## Recommended next actions

**Block Phase 2 start until C1, C2, C3, C4 are remediated.** These are not "nice to fix"; they are foundational safety properties of a stub-driven adapter. Specifically:

1. **C1 — Stub indicator.** Add startup banner + `X-NubBank-Stubbed: true` header + change stubbed BVN/NIN data to `00000000000`. ETA: 1-2 hours.
2. **C2 — Inter-service auth.** Decide mTLS vs shared HMAC. Document the choice. Implement the validation filter. ETA: 1 day.
3. **C3 — Stub realism.** Make `StubNpsHttpClient` parse the request, echo destination back, and reject obviously-invalid inputs. ETA: 4 hours.
4. **C4 — Idempotency.** Add Redis (or DB) `(idempotency-key → result)` cache with 24h TTL. Required before any NIP go-live. ETA: 1 day.
5. **C5 — PII masking.** Add `MaskingUtil`, apply at all log sites; add Logback masking filter. ETA: half a day.
6. **C6 — XSD-bound XML.** Replace string-template builder with JAXB from the actual NPS XSDs. The string-template builder is a Phase 2 liability — the rewrite is unavoidable; better to do it before partners build against the current shape. ETA: 2-3 days.

**Before Phase 2 (live NIBSS):**
- Add CI workflow (`baas-ncube-ci.yml`) with mvn test, OWASP Dep-Check, SpotBugs.
- Add `NpsHealthIndicator` (I2) so health check actually means something.
- Restrict `permitAll()` and `/actuator/**` (I1, C2).
- Apply CBN vendor media type on controllers (I3).

**During Phase 2:**
- Replace stubs with real Apache Santuario signing + AES-256-GCM/RSA-OAEP encryption.
- Add mTLS to NIBSS endpoint (NIBSS-issued certs).
- Add per-TPP rate limiting on `/baas/v1/ncube/payments/nip` to protect NIBSS quota.
- Implement the idempotency-key store (C4) — must be live before first real payment.
- Validate every outbound XML against the NPS XSDs before transmission.

**Process / governance:**
- Self-merge of regulatory-adjacent code with zero recorded review activity is the largest finding here. Require at least one human reviewer (and ideally a second eyes on identity / payment files specifically) for all `baas-ncube/` and `baas-engine/openbanking/` paths going forward. Codify in `CODEOWNERS`.
