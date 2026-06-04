# Phase 1C — Cross-Track Interface Contracts

Foundation publishes these so parallel tracks build against stable shapes.

## 1. Operator authority claim format (Backoffice, all backend tracks)
- Operators authenticate via Keycloak realm `baas-partner-{uuid}`; token `iss` = realm issuer URL.
- The issuer is registered on `public.partner_organizations.keycloak_issuer` (unique); only partners
  whose status `isActiveForAuth()` (SANDBOX/BASIC/PRO/ENTERPRISE, not SUSPENDED/PENDING_REVIEW) resolve.
- `sub` = operator UUID; it is the join key to tenant-schema `user_roles.user_id` (no FK — see spec §6.2.4).
- Spring authorities = `permissions.code` values granted to the operator's roles (authMode `OPERATOR_JWT`).
- First-party credentials (API key, HMAC partner-login JWT) carry the **full** tenant authority set
  (authMode `API_KEY` / `JWT`). Granular RBAC for HMAC users is deferred (DEF-1C-15).
- `@PreAuthorize("hasAuthority('<CODE>')")` is the gate; denials return HTTP 403 with the standard
  `{ "data": null, "errors": [{ "code": "ACCESS_DENIED", ... }] }` envelope (via GlobalExceptionHandler).

## 2. BIN-lookup contract (Card → FEP)
- `baas-card` exposes `GET /internal/v1/bins/{bin}` (HMAC inter-service auth) →
  `{ "partnerId": "<uuid>", "schemaName": "partner_<uuid>" }` or 404. **Implemented by Track-Card** (Task 2).
- `baas-fep` calls this after extracting DE2 PAN → 6/8-digit BIN; caches 5 min (Caffeine).
- Unknown BIN → FEP returns ISO 8583 RC `91`, no PAN echo.
- **BIN normalization (shared invariant — BOTH tracks MUST implement identically):** take the PAN's leading
  digits, keep the first 8 (or fewer if the PAN is shorter), then **left-align and zero-pad to 8 chars**.
  Card stores `bin_start`/`bin_end` in this normalized 8-char form and range-matches on it
  (`BinService.normalize`); FEP normalizes the PAN the same way before lookup (`BinResolver.bin`). If either
  side diverges, every lookup misses. Range match: `bin_start <= bin8 AND bin_end >= bin8 AND active`.

## 2a. Card authorization-decision contract (FEP → Card)

- `baas-card` exposes `POST /internal/v1/authorize` (HMAC inter-service auth). **Implemented by Track-Card**
  (Task 6); **consumed by Track-FEP** (Task 6).
- Request body (field-for-field identical on both sides — `AuthorizationDecisionRequest` on Card,
  `AuthorizationDecision.Request` on FEP):
  `{ "partnerId": "<uuid>", "schemaName": "partner_<uuid>", "pan": "<full PAN from DE2>",
     "amountMinor": <long, minor units>, "currency": "<ISO 4217 numeric, e.g. 566>",
     "stan": "<DE11, 6-digit Systems Trace Audit Number>",
     "terminalId": "<DE41, 8-char terminal identifier>",
     "transmissionDateTime": "<DE7, MMDDhhmmss>" }`.
- Response body: `{ "decision": "APPROVE|DECLINE", "responseCode": "<ISO 8583 DE39>", "message": "<text>" }`.
- Card resolves the card by **`pan_hash` = HMAC-SHA256(app.encryption.key, pan)** (the FEP holds only the PAN,
  never a card id). RC mapping (Phase 1C): `00` approve · `56` no such card · `62` blocked/cancelled ·
  `54` not usable (ISSUED/EXPIRED) · `61` exceeds per-txn limit (evaluated in the transaction currency,
  scaled by its ISO 4217 minor-unit exponent) · `57` currency not permitted (limit currency mismatch) ·
  `12` unknown currency code.
  Balance check is a stub (always sufficient, deferred DEF-1C-23).
- **Authorization idempotency:** Card persists an idempotency row keyed by
  `idem_key = stan + "|" + terminalId + "|" + transmissionDateTime` (these three DE values never contain `|`).
  A duplicate request returns the cached decision without re-evaluating. Rows are purged nightly (24-hour
  retention). The UNIQUE constraint and the lookup both target `idem_key` alone, so they always agree.
- **Cross-service shape parity** is guarded by a per-module reflection test (`AuthorizationContractShapeTest`
  in **both** `baas-card` and `baas-fep`), since they are separate Maven modules that cannot share a single
  reflection test.
- **PAN safety:** neither side logs the PAN; FEP omits DE2 from any unrouteable (`91`) response.
- **Envelope:** both internal endpoints (§2 lookup + §2a authorize) return the standard `ApiResponse` envelope
  `{ "data": {...}, "meta": {...}, "errors": null }`. The shapes above are the `data` payload — FEP's
  `HttpCardClient` reads `.data`. (Stage-5 integration verifies this live; FEP unit tests mock `CardClient`.)

> **Track-FEP consumption confirmed (Session 9, `baas-fep`).** Track-FEP consumes §2 and §2a as built, with no
> change to the frozen shapes:
> - §2 BIN normalization is implemented in `BinResolver.bin(...)` exactly as specified — take ≤8 leading PAN
>   digits, left-align, zero-pad to 8 (`String.format("%-8s", head).replace(' ', '0')`); null PAN → `"00000000"`.
>   This must stay identical to Card's `BinService.normalize(...)` (shared invariant — divergence misses every lookup).
> - §2a request/response are mirrored field-for-field by `AuthorizationDecision.Request(partnerId, schemaName,
>   pan, amountMinor, currency, stan, terminalId, transmissionDateTime)` and
>   `AuthorizationDecision(decision, responseCode, message)`.
> - Envelope: `HttpCardClient` reads `.data` on both endpoints; 404 / transport error → unrouteable (`Optional.empty()`
>   for BIN lookup) or fail-safe `DECLINE`/`96` for authorize, so the Netty thread never sees a checked exception.
> - PAN safety: PAN is never logged (masked to `****<last4>` in `Request.toString`) and DE2 is omitted from any
>   unrouteable (`91`) response. These are FROZEN — neither side changes them unilaterally (per the contract-change rule).

## 2b. Reversal-decision contract (FEP → Card)

- `baas-card` exposes `POST /internal/v1/reversal` (HMAC inter-service auth). **Implemented by Track-Card**
  (Session 11 seam hardening); **consumed by Track-FEP** (Session 11 seam hardening).
- This is an internal endpoint only — not partner-facing.
- **Request body** (`ReversalDecisionRequest` on Card, `ReversalDecision.Request` on FEP):
  `{ "partnerId": "<uuid>", "schemaName": "partner_<uuid>",
     "originalStan": "<DE90 original STAN, 6 digits>",
     "terminalId": "<DE41, 8-char terminal identifier>",
     "originalTransmissionDateTime": "<DE90 original transmission date-time, MMDDhhmmss>" }`.
  The FEP composes `originalStan` and `originalTransmissionDateTime` from **ISO 8583 DE90 (Original Data
  Elements)** of the `0400` reversal message.
- **Response body:** `ApiResponse<{ "located": boolean }>`.
- **RC mapping by FEP:** `located: true` → DE39 `00` (reversal accepted); `located: false` → DE39 `25`
  (unable to locate original transaction).
- **Phase 1C scope:** Card locates the original authorization row via the per-tenant idempotency table
  (matched by `originalStan + "|" + terminalId + "|" + originalTransmissionDateTime`) and marks it
  `reversed = true`, returning `located: true`. If no row is found, returns `located: false`.
  **The actual fund reversal (crediting the cardholder account) is deferred to Phase 2** — it rides with
  the real balance-check wiring (DEF-1C-23). Phase 1C only locates and marks the idempotency row.

## 3. Admin namespace reservation (Custodian, Platform-Admin)
- `/baas-admin/v1/**` is reserved for the NubBank admin chain.
- Foundation scoped the partner chain to `@Order(2)` + `securityMatcher("/baas/v1/**", "/actuator/**",
  "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")` (bean `partnerFilterChain`).
- The Custodian track adds `@Order(1) SecurityFilterChain adminFilterChain` with
  `securityMatcher("/baas-admin/v1/**")`, routed to the `baas_readonly_admin` datasource.
- NOTE for the Custodian track: `RateLimitFilter` carries a servlet-`Filter` `@Order(1)` — this is a
  SEPARATE ordering namespace from `SecurityFilterChain` `@Order`. They do not collide, but when adding
  the `@Order(1)` admin chain, add a clarifying comment to `RateLimitFilter`'s `@Order` so the two
  `Order(1)`s are never confused.
- Admin JWTs carry NO `partner_id`; the admin-issuer (`app.keycloak.admin-issuer`) is recognised by
  `OperatorJwtResolver.isAdminIssuer(...)` and is rejected on the partner API (no PartnerContext set →
  401 via AuthEnforcementFilter).
- NOTE for the Custodian track: `PartnerContextFilter`, `AuthEnforcementFilter`, and `RateLimitFilter` are
  `@Component OncePerRequestFilter` beans, so Spring Boot auto-registers them at the servlet level IN ADDITION
  to the explicit `addFilterBefore/After` wiring in the partner chain. This is benign with a single chain
  (OncePerRequestFilter prevents double execution). When adding the `@Order(1)` admin chain, register a
  `FilterRegistrationBean` with `setEnabled(false)` for these filters (or drop `@Component` and wire them only
  via the chains) so servlet-level registration order cannot diverge from the security-chain order across two
  chains.
