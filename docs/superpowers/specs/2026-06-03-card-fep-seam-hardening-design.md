# Card/FEP Seam Hardening — Design Spec

**Date:** 2026-06-03
**Author:** Session 11 (brainstorming)
**Status:** Approved (design) — pending spec review → implementation plan
**Scope:** `baas-card` + `baas-fep`. No `baas-engine` changes.

---

## 1. Purpose

An Expert Review of the `baas-card` (Session 10) and `baas-fep` (Session 9)
implementations — both merged to `main`, both green (56 / 46 tests) — found eight
issues on the cross-service authorization seam. The two services were built in
parallel against **mocks of each other**, so the contract seam (HMAC canonical
string, BIN normalization, the authorize DTO) was never checked against its real
counterpart.

This spec hardens that seam **before Stage 5 wiring** (the live FEP→Card→Engine
integration), so Stage 5 builds on a correct foundation rather than amplifying
latent defects across three services under live money movement.

**This pass does NOT implement the real balance check or actual fund movement —
those are Stage 5 / Phase 2 by definition.** See § 9 Non-Goals.

---

## 2. Findings being fixed

| # | Finding | Severity | Touches frozen §2a contract? |
|---|---------|----------|------------------------------|
| **F1** | Decimal scaling hardcoded to 2dp (`movePointLeft(2)`) — wrong for 0/3-exponent currencies (JPY=0, KWD/BHD/TND=3) | Money bug | No (currency already in contract) |
| **F2** | Per-txn limit compared currency-blind (`CardLimit` has no currency) | Money bug | No |
| **F3** | No idempotency / no STAN forwarded — ISO retransmit double-authorizes once real holds land | Correctness (high) | **Yes — adds 3 fields** |
| **F4** | Replay window inconsistent (card 300s vs engine 60s); no nonce | Security | No |
| **F5** | No sandbox/prod segregation — authorize hardcodes `environment="PRODUCTION"` | Isolation (high) | No (derivable from schema prefix) |
| **F6** | Reversal blanket-approves (`0400`→RC00) for transactions that never happened | Correctness | Adds DE90 to packager + new card endpoint |
| **F7** | Single short BIN registered `start==end` under-covers its own cards | Usability | No |
| **F8** | HMAC path-source asymmetry (client decoded path vs server raw path) | Latent | No |

---

## 3. Design decisions (ratified during brainstorming)

- **F3 key shape (A1):** three discrete ISO fields (`stan`, `terminalId`,
  `transmissionDateTime`) — NOT a single pre-composed key. Card composes the dedup
  key itself; the discrete fields also feed F6 reversal matching and a future auth log.
- **F3 enforcement scope:** full dedup now (real per-tenant table + cached-decision
  replay), not contract-key-only.
- **F2 reference currency:** add `currency_code` to `CardLimit`; enforce on match,
  **decline (RC 57) on mismatch** (including null limit currency with a set amount) —
  fail-safe on the money path. Invisible for an NGN-only launch.
- **F1 exponent source:** JDK-derived from `java.util.Currency` (no hand-maintained
  table). **Unknown numeric code → decline (RC 12)**, fail-loud.
- **F4:** reconcile card window to 60s; **no nonce store** (idempotency table covers
  authorize replay; BIN lookup is an idempotent GET; internal HMAC path is trusted-network).
- **F5:** derive environment from schema prefix; no new column, no contract change.
- **F6 (full B):** add **DE90** to the FEP packager + a new card
  `POST /internal/v1/reversal` endpoint that locates the original in the idempotency
  table and marks it reversed. Actual fund-reversal rides Phase 2.
- **F7:** registration pads `binEnd` with `9` (start still `0`); frozen lookup
  normalization untouched.
- **F8:** both sides sign/verify the raw (un-decoded) path.

---

## 4. Contract change — frozen §2a amendment (F3)

Both records gain three fields and MUST remain field-for-field identical:

`baas-card` `AuthorizationDecisionRequest` and `baas-fep`
`AuthorizationDecision.Request`:

```java
String partnerId,
String schemaName,
String pan,
long   amountMinor,
String currency,
String stan,                 // DE11
String terminalId,           // DE41
String transmissionDateTime  // DE7, MMDDhhmmss
```

A reflection-based **contract parity test** (in one service) asserts the two records
have identical component names + types, so they cannot silently diverge again.

---

## 5. card-service changes

### 5.1 F1 — Currency minor-unit scaling
- New `CurrencyMinorUnits` Spring bean. At construction, build
  `Map<String,Integer>` from `Currency.getAvailableCurrencies()`:
  key = `String.format("%03d", c.getNumericCode())`, value = `c.getDefaultFractionDigits()`.
  Exclude entries with `getNumericCode() <= 0` or `getDefaultFractionDigits() < 0`
  (pseudo-currencies such as XXX).
- API: `Optional<Integer> exponentFor(String numericCode)`.
- `AuthorizationDecisionService` scales `amountMinor` using the resolved exponent
  (`new BigDecimal(amountMinor).movePointLeft(exponent)`); **unknown currency →
  `decline("12")`** before any card lookup.

### 5.2 F2 — Currency-aware per-txn limit
- Migration adds `card_limits.currency_code VARCHAR(3)`.
- `CardLimit` entity gains `currencyCode`; the limit upsert requires it when any
  amount is non-null.
- Decision: enforce `perTxn` only when `limit.currencyCode != null &&
  limit.currencyCode.equals(txnCurrency)`. Otherwise (mismatch or null currency with
  a set amount) → `decline("57")`.

### 5.3 F3 — Authorization idempotency
- Migration adds per-tenant table `authorization_idempotency`:
  `id UUID PK`, `idem_key VARCHAR UNIQUE NOT NULL`, `decision VARCHAR`,
  `response_code VARCHAR(2)`, `message VARCHAR`, `reversed BOOLEAN NOT NULL DEFAULT false`,
  `created_at TIMESTAMPTZ NOT NULL`.
- `idemKey = stan + "|" + terminalId + "|" + transmissionDateTime`. The table is
  per-tenant (partner schema), so keys are naturally tenant-scoped.
- `decide()` flow (inside the existing set-context / `finally`-clear block):
  1. compute `idemKey`; look up **by `idem_key` alone** (no time predicate — retention
     is enforced solely by the purge job below, so lookup and the UNIQUE constraint
     agree on exactly one row per key).
  2. hit → return the cached decision (do **not** re-run the decision logic).
  3. miss → run the decision logic, persist `(idemKey, decision, rc, message)`, return.
  4. unique-constraint race (concurrent first-time) → catch, re-read by `idem_key`,
     return existing.
- Retention = the **24h window**, enforced by a daily `@Scheduled` purge that deletes
  rows with `created_at < now-24h`. The purge lives in a separate `@Service` bean so
  the `@Transactional` boundary is real (per project gotcha on self-invocation).
  Because lookup is keyed on `idem_key` (not a windowed predicate), there is no
  stale-row-beyond-window edge: a key is present until the purge removes it, and a
  genuine retransmit always occurs well within 24h.

### 5.4 F5 — Environment from schema prefix
- `AuthorizationDecisionService` sets `environment =
  schemaName.startsWith("sandbox_") ? "SANDBOX" : "PRODUCTION"` in the synthetic
  `PartnerContext` (replaces the hardcoded `"PRODUCTION"`). `tier` stays `"INTERNAL"`
  (not consulted by the decision).

### 5.5 F6 — Reversal endpoint
- New `POST /internal/v1/reversal` (`InternalReversalController` + `ReversalService`).
- Request DTO: `partnerId, schemaName, originalStan, originalTransmissionDateTime,
  terminalId`.
- Service (set context from schema, `finally` clear): compose the original
  `idemKey`, look it up in `authorization_idempotency`.
  - not found → `{ located: false }`.
  - found & not reversed → set `reversed = true`, `{ located: true }`.
  - found & already reversed → `{ located: true }` (idempotent).
- Response DTO: `{ located: boolean }`, carried in the standard `ApiResponse` envelope.
- Lives on the `@Order(1)` internal HMAC chain (no partner auth), same as authorize.

### 5.6 F4 — Replay window
- `InternalServiceAuthFilter.MAX_SKEW_SECONDS`: `300` → `60`.

### 5.7 F7 — BIN range registration coverage
- Add `BinService.normalizeRangeEnd(String)`: same as `normalize()` but right-pads
  the digit head with `'9'` instead of `'0'`.
- `register()` uses `normalize(binStart)` for the start and `normalizeRangeEnd(binEnd)`
  for the end. The frozen lookup `normalize()` (PAN → first-8 → right-pad-`0`) is
  **unchanged**, preserving the cross-track invariant.
- Result: `register("506000","506000")` stores `[50600000, 50600099]`, covering all
  PANs beginning `506000`.

---

## 6. fep-service changes

### 6.1 F3 — Forward ISO trace fields
- `AuthorizationHandler.handle()` extracts DE11 (STAN), DE41 (terminal), DE7
  (transmission date-time) and passes them into `AuthorizationDecision.Request`.
- `AuthorizationDecision.Request` record gains the three fields (§ 4); `toString()`
  still masks PAN.

### 6.2 F6 — DE90 + reversal wiring
- Add `IsoField.ORIGINAL_DATA = 90`.
- Add DE90 to `iso8583-1987-fields.xml`: fixed length **42** (origMTI 4 + origStan 6 +
  origDateTime 10 + acquirerId 11 + forwarderId 11).
- `ReversalHandler.handle()`:
  - resolve partner route via DE2 → `BinResolver` (the `0400` carries the PAN).
    No route → RC `91` (consistent with authorize; DE2 omitted from response).
  - parse DE90: original STAN (chars 5–10), original transmission date-time (11–20).
  - call new `CardClient.reverse(...)` with `{partnerId, schemaName, originalStan,
    originalTransmissionDateTime, terminalId=DE41}`.
  - located → RC `00`; not-located → RC `25`; transport error → fail-closed RC `96`.
  - echo only STAN + TRANSMISSION_DTS; never set DE2 on the response.
- New `ReversalDecision` DTO + `CardClient.reverse` + `HttpCardClient.reverse`
  (fail-closed: transport error → not-located/RC 96 path, never throws into the Netty
  thread).

### 6.3 F8 — HMAC raw path
- `CardClientConfig.SigningInterceptor`: `request.getURI().getPath()` →
  `request.getURI().getRawPath()`. Card validator already uses `getRequestURI()` (raw).

---

## 7. Migrations

- **`baas-card/.../db/migration/card-tenant/V2__authorization_idempotency_and_limit_currency.sql`**
  - `CREATE TABLE authorization_idempotency (...)` (§ 5.3).
  - `ALTER TABLE card_limits ADD COLUMN currency_code VARCHAR(3);`
  - runs under `flyway_schema_history_card` (existing card history table).
- No `card-public` or contract-incompatible changes.

---

## 8. Testing

**card-service**
- `CurrencyMinorUnits`: 2-dp (NGN 566), 0-dp (JPY 392), 3-dp (KWD 414); unknown → empty.
- `AuthorizationDecisionService`: scaling per exponent; unknown currency → RC 12;
  limit currency match enforces; mismatch / null-currency-with-amount → RC 57;
  idempotency replay returns cached decision without re-deciding; env derived
  `SANDBOX` vs `PRODUCTION` from schema.
- Reversal endpoint: located → reversed + `located:true`; not-found → `located:false`;
  already-reversed → idempotent `located:true`.
- BIN registration: 6-digit `start==end` covers the full sub-range; lookup hits.
- `InternalServiceAuthFilter`: existing tests still pass at the 60s window.

**fep-service**
- `AuthorizationHandler`: STAN/terminal/DTS forwarded into the request.
- DE90 packager round-trip (pack → unpack → field values intact).
- `ReversalHandler`: located → RC 00; not-located → RC 25; transport error → RC 96;
  unrouteable BIN → RC 91 with DE2 absent.
- HMAC raw-path signing: a path with an encoded segment verifies (parity guard for F8).

**cross-service**
- Contract parity reflection test (both authorize DTOs identical in name + type).

---

## 9. Non-goals (explicitly NOT in this pass)

- **Real balance check (DEF-1C-23)** — this is the Stage 5 / Phase 2 wiring.
- **Actual fund-movement reversal** — F6 only *detects* and *marks* the original
  reversed; reversing money rides Phase 2 with the balance check.
- **Nonce store (F4)** — decided against; window + idempotency table suffice.
- **Full FEP-side authorization log (DEF-1C-24)** — card's idempotency table partially
  overlaps; the dedicated FEP log stays deferred.

---

## 10. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Frozen §2a contract drifts again between the two services | Reflection-based contract parity test (§ 4). |
| Idempotency write race on concurrent first-time auth | UNIQUE on `idem_key` + catch-duplicate-then-re-read (§ 5.3). |
| ThreadLocal leak in the new reversal endpoint | Same set-from-schema + unconditional `finally` clear as `decide()` (§ 5.5 gotcha). |
| DE90 length/format mismatch with terminals | Fixed 42-char layout per ISO 8583:1987; packager round-trip test (§ 8). |
| Existing `card_limits` rows have null `currency_code` | Treated as mismatch → RC 57 (fail-safe). No production data pre-launch. |
