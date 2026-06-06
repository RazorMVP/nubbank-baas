# Stage 5 — Card↔Engine Money Wiring Design

**Date:** 2026-06-04
**Status:** Ratified (brainstorming complete; ready for implementation plan)
**Closes deferred items:** DEF-1C-23 (real balance check), DEF-1C-25 (reversal fund movement),
DEF-1C-22 (cross-service provisioning trigger), DEF-1C-24 (FEP authorization audit log)
**Opens deferred item:** DEF-1C-27 (platform-wide automatic GL double-entry posting)

---

## 1. Goal

Replace the stubbed, money-free card authorization seam (Session 11) with a real, banking-correct
money path: an approved card authorization **debits the cardholder's engine account**, and a
reversal **credits it back** — wired live across `baas-fep → baas-card → baas-engine` over the
existing body-signed HMAC inter-service seam, with cross-service idempotency and fail-closed
behaviour. Also provisions card-tenant schema objects automatically when the engine onboards a
partner, and gives the FEP a durable authorization audit trail.

This is **single-message** money movement (ISO 8583:1987 `0200`-style financial semantics): the
authorization *is* the financial event. There is no settlement/clearing layer (DEF-1C-04 remains
deferred), so a hold model is deliberately rejected.

## 2. Ratified decisions

| # | Decision | Choice |
|---|----------|--------|
| Scope | Which deferred items | All four: DEF-1C-23 + DEF-1C-25 (fund half) + DEF-1C-22 + DEF-1C-24 |
| Card→account mapping | How a card resolves to an engine account | Bind at issuance: `linkedAccountId` on `Card` + `IssueCardRequest` (`@NotNull`), validated against the engine at issuance |
| Money semantics | What an approved auth does | **Single-message immediate debit**; reversal credits back |
| Decline codes | New failure RCs | `51` insufficient · `78` linked account missing/not-ACTIVE · `91` engine unreachable; `57` also covers account-currency mismatch |
| Provisioning | How card schema is created on partner onboard | **Synchronous engine→card** `POST /internal/v1/provision` after engine migrations; card failure fails the whole provisioning (no half-state); idempotent |
| Atomicity | Cross-service money correctness | **Approach A** — engine is the money-dedupe authority (atomic debit+dedupe in one engine-schema transaction, idempotent on `authKey`); card calls engine first, then records the decision row |
| Unit scaling | Who converts minor↔major units | Card scales (`amountMinor → BigDecimal`); engine works in major units only |
| Currency representation | Numeric (DE49) vs alphabetic (engine account) | Card is the single owner of currency translation: FEP→card stays **ISO 4217 numeric** (frozen `"566"`); card translates to **ISO 4217 alphabetic** (`"NGN"`) on the new card→engine seam so the engine compares alpha-to-alpha in its native convention |
| GL posting | Double-entry journal entries | **Out of scope** — reuse single-`Transaction` path; register DEF-1C-27 |

ISO 8583 version is **unchanged: 1987** (jPOS `GenericPackager`, `iso8583-1987-fields.xml`). Stage 5
adds no ISO fields and no MTI/version change. The reversal relies on **DE90 (Original Data Elements)**
to rebuild the original `authKey` — this is specific to the 1987 layout and was frozen in Session 11.

## 3. Architecture & data flow

```
[Terminal] ──ISO8583:1987──▶ [baas-fep :8082]
                              │  BIN→tenant, build authorize/reversal
                              │  persist decision → fep.authorization_log   (DEF-1C-24, best-effort)
                              ▼  HMAC  /internal/v1/authorize | /internal/v1/reversal
                            [baas-card :8081]
                              │  local checks (status/limit/currency) + idempotency cache
                              │  outbound HMAC signer (NEW)
                              ▼  HMAC  /internal/v1/card-debit | /internal/v1/card-credit
                            [baas-engine :8080]
                              inbound InternalServiceAuthFilter (NEW)
                              atomic debit/credit + card_auth_debit dedupe (NEW)
```

`authKey = stan | terminalId | transmissionDateTime` — the single idempotency key threaded through
all services (DE11 numeric, DE41 alphanumeric, DE7 numeric never contain `|`). For reversals the FEP
rebuilds the same key from DE90 (`originalStan` + `originalTransmissionDateTime`) + DE41.

### Flow 1 — Authorize (single-message debit)
1. FEP → Card `POST /internal/v1/authorize` (frozen contract §2a, plus new outcome codes).
2. Card sets `PartnerContext` from `schemaName`, computes `authKey`.
3. **Fast path:** a *final* card decision row exists for `authKey` → return it, no engine call.
4. Local pre-checks: currency exponent invalid → `12`; no card → `56`; BLOCKED/CANCELLED → `62`;
   not ACTIVE → `54`; limit currency ≠ txn currency → `57`; amount > per-txn → `61`. Any decline →
   record row, return — **no engine call** (nothing to debit).
5. Would-approve → `card.linkedAccountId` null → `78`; else Card → Engine
   `POST /internal/v1/card-debit {partnerId, schemaName, accountId, authKey, amount, currency}`
   (`amount` is major-unit `BigDecimal`, already scaled by the card; `currency` is the **ISO 4217
   alphabetic** code, translated by the card from the DE49 numeric).
6. Engine, in **one transaction**: if `authKey` already in `card_auth_debit` → return stored outcome
   (no re-debit); else lock account, validate, floor-check, debit, write `Transaction(DEBIT)`, write
   `card_auth_debit`, return outcome.
7. Card maps outcome → RC: `DEBITED→00` · `INSUFFICIENT→51` · `ACCOUNT_INVALID→78` ·
   `CURRENCY_MISMATCH→57` · unreachable/timeout/5xx → `91` (**fail closed**). Card records the
   decision row (existing race-safe insert). Returns to FEP.

### Flow 2 — Reversal (credit back)
1. FEP → Card `POST /internal/v1/reversal` (frozen contract §2b shape).
2. Card locates its original decision row by the DE90-derived key.
   - Found, `decision=APPROVE`, `reversed=false` → Card → Engine
     `POST /internal/v1/card-credit {…, authKey}`; `located:true` ⇒ flip card `reversed`, RC `00`.
   - Found, `decision=DECLINE` → nothing was debited ⇒ flip `reversed`, RC `00`.
   - Not found → RC `25`.
   - **Engine unreachable while crediting an APPROVE → RC `25`, do NOT flip `reversed`.** Terminals
     retry reversals; `card-credit` is idempotent on `authKey`, so a later retry completes. (`25`
     used here as "couldn't complete — retry", within the frozen 00/25 vocabulary.)
3. Engine `card-credit`, one transaction, idempotent: `card_auth_debit` row is `DEBITED` &
   `reversed=false` → credit account, write reversing `Transaction(CREDIT)`, set `reversed=true`,
   return `{located:true}`. Already reversed → no-op `{located:true}`. Not found / never DEBITED →
   `{located:false}`.

### Flow 3 — Provisioning (DEF-1C-22)
Engine `provision()` → after its own migrations succeed and before logging `SUCCESS` to
`public.schema_provision_log` → HMAC call to Card `POST /internal/v1/provision {partnerId,
schemaName}` → card runs its tenant migrations into both `partner_*` and `sandbox_*`. Card failure →
engine throws → row logged `FAILED`. Whole `provision()` re-run is idempotent
(`CREATE SCHEMA IF NOT EXISTS` + Flyway).

**Invariant:** every atomic operation lives inside the service that owns the data (engine debit+dedupe
is one engine-schema transaction; the card decision-row insert is one statement). The shared `authKey`
is what makes a crash anywhere recoverable by a plain retry. There is **no** distributed transaction /
2PC.

## 4. Engine-side changes (`baas-engine`)

### 4.1 Inbound HMAC seam
- **New** `InternalServiceAuthFilter` (engine) — byte-for-byte the scheme card already validates:
  `HmacSHA256(secret, METHOD | rawPath | epochSeconds | sha256Hex(body))`, headers
  `Authorization: Internal <hex>` + `X-Internal-Timestamp`, **60s** skew, `≥32-char` secret checked at
  construction (fail-fast at boot). `shouldNotFilter` guards only paths starting `/internal/v1/`.
- **Security wiring:** add a dedicated `@Order(0) SecurityFilterChain internalFilterChain` with
  `securityMatcher("/internal/v1/**")`, `permitAll` at the Spring layer (the HMAC filter is the real
  gate), CSRF disabled, stateless. The existing `@Order(2) partnerFilterChain` is unchanged.
- The engine's auto-registered `@Component OncePerRequestFilter`s — `PartnerContextFilter`,
  `AuthEnforcementFilter`, `RateLimitFilter` — each get a `shouldNotFilter` skip for `/internal/v1/`
  so they neither 401 nor rate-limit nor set partner context from JWT on the internal path. (Mirrors
  the §3 double-registration note and how card already isolates its internal path.)

### 4.2 PartnerContext discipline (documented pitfall)
The internal controller sets `PartnerContext` from the **request body** (`partnerId`/`schemaName`)
and invokes the `@Transactional` service method *inside* that set, clearing in `finally`. Context MUST
be set **before** the transaction opens — opening the Hibernate session first routes to `public` and
the tenant tables disappear (DEF-1C-23 note).

### 4.3 `card_auth_debit` dedupe table (engine **tenant** schema; new tenant migration `V4__card_auth_debit.sql`)

| column | type | note |
|---|---|---|
| `id` | UUID PK | |
| `auth_key` | VARCHAR(120) **UNIQUE NOT NULL** | `stan\|terminalId\|transmissionDateTime` |
| `account_id` | UUID NOT NULL | the debited account |
| `amount` | NUMERIC(19,4) NOT NULL | major units |
| `currency_code` | VARCHAR(3) NOT NULL | ISO 4217 **alphabetic** (matches `accounts.currency_code`) |
| `outcome` | VARCHAR(20) NOT NULL | `DEBITED` / `INSUFFICIENT` / `ACCOUNT_INVALID` / `CURRENCY_MISMATCH` |
| `transaction_id` | UUID NULL | FK→`transactions`, set only when `DEBITED` |
| `reversed` | BOOLEAN NOT NULL DEFAULT false | |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |

Created by an engine tenant migration, so the DEF-1C-22 trigger auto-creates it per partner.

### 4.4 `POST /internal/v1/card-debit` → `AccountService.cardAuthorizationDebit(...)`, one `@Transactional`
1. Look up `card_auth_debit` by `auth_key` → if present, return stored outcome (idempotent; no second debit).
2. Else `findByIdForUpdate(accountId)` (pessimistic lock). Missing or not `ACTIVE` → persist
   `ACCOUNT_INVALID`, return.
3. Account `currency_code` ≠ request currency (both ISO 4217 **alphabetic** — the card has already
   translated DE49 numeric → alpha) → persist `CURRENCY_MISMATCH`, return (no FX).
4. Floor check (reuse `withdraw()`'s rule: overdraft limit if `allowOverdraft`, else minimum balance).
   Fails → persist `INSUFFICIENT`, return.
5. Debit `balance` + `available_balance`, write immutable `Transaction(DEBIT)`, persist
   `card_auth_debit(DEBITED, transactionId)`, return.

A new `AccountService` method (not partner-facing `withdraw()`), returning a structured outcome enum
rather than throwing `BaasException`, so the seam maps cleanly to RCs.

### 4.5 `POST /internal/v1/card-credit` → `AccountService.cardAuthorizationCredit(authKey)`, one `@Transactional`, idempotent
- `card_auth_debit` for `authKey` is `DEBITED` & `reversed=false` → credit `balance` +
  `available_balance`, write reversing `Transaction(CREDIT)`, set `reversed=true` → `{located:true}`.
- Already `reversed` → no-op → `{located:true}`.
- Not found, or outcome never `DEBITED` → `{located:false}`.

### 4.6 Outbound card-provisioning client
- `CardProvisioningClient` over the engine's existing-but-unused `InternalServiceClient`
  (`@Qualifier("internalServiceRestTemplate")`). Calls card `POST /internal/v1/provision`. New config
  `app.card.base-url`. Wired into `TenantProvisioningService.provision()` per Flow 3.

## 5. Card-side changes (`baas-card`)

### 5.1 Outbound HMAC signer
- **New** `InternalServiceClient` config (mirror of engine's): signed `RestTemplate`
  (`@Qualifier("internalServiceRestTemplate")`, `SigningInterceptor` using `getRawPath()`, `≥32-char`
  secret at boot). Base URL `app.engine.base-url`; connect 5s / read 10s (money path fails fast).
- A thin `EngineClient` wrapper: `cardDebit(...)`, `cardCredit(...)`. **Fail-closed:** any
  `RestClientException` / timeout / non-2xx → sentinel (debit → RC `91`; credit → "not completed",
  §5.5).

### 5.2 `linkedAccountId` binding
- Add `linked_account_id UUID` to `cards` (new card-tenant migration `V3__linked_account_id.sql`),
  field on `Card`, **`@NotNull linkedAccountId`** on `IssueCardRequest`.
- **Issuance validates the account exists** via an engine call (`EngineClient`), rejecting an unknown
  account before the card is created. Off the hot path; prevents binding to a non-existent account.
- Pre-existing rows are null → authorize declines `78`.

### 5.3 Minor→major scaling + currency-code translation boundary
The card is the **single owner** of currency knowledge (`CurrencyMinorUnits`). It performs **both**
translations before the engine call:
- **minor → major units:** `amountMinor → BigDecimal` via the currency's minor-unit exponent;
- **DE49 numeric → ISO 4217 alphabetic:** e.g. `"566" → "NGN"`, via `java.util.Currency` over the
  same set `CurrencyMinorUnits` already iterates (add an `alphaFor(numericCode)` lookup alongside the
  existing `exponentFor`). A numeric code with no alpha mapping cannot occur once `exponentFor`
  succeeds — both derive from the same `Currency` set, so the existing `12` unknown-currency guard
  already covers it.

The engine receives a scaled major-unit `BigDecimal amount` + an **alphabetic** currency and compares
`account.currencyCode == request currency` alpha-to-alpha (its native convention). The engine never
scales and never translates — a unit *or* currency-representation bug can only originate in one file.

### 5.4 Rewired authorize (`AuthorizationDecisionService`)
Local-check order unchanged; the engine call is appended to the would-approve branch (Flow 1 steps
4–7). Fast-path idempotency cache and "local declines make no engine call" are preserved exactly.
`computeDecision` gains an engine call only on the would-approve branch; `PartnerContext` is set for
the whole `decide()` and the engine call carries `partnerId`/`schemaName` in the body so the engine
routes to the same partner schema. `decide()` remains **not** `@Transactional` (the documented
context-before-session pitfall).

### 5.5 Rewired reversal (`ReversalService`)
Per Flow 2: credit via engine only when the original was an APPROVE and not yet reversed; engine
authority prevents double-credit even if the card flag races; engine-unreachable on an APPROVE credit
→ RC `25` without flipping `reversed` (safe retry).

### 5.6 Full authorize RC map after Stage 5
`00` approved · `12` unknown currency · `51` insufficient · `54` card not usable · `56` no card ·
`57` currency not permitted (limit *or* account mismatch) · `61` over per-txn limit · `62` restricted ·
`78` no/inactive linked account · `91` engine unreachable.

### 5.7 Card-side provisioning endpoint
**New** `POST /internal/v1/provision {partnerId, schemaName}`, guarded by card's existing
`InternalServiceAuthFilter`, calling card's idempotent `TenantProvisioningService.provision(...)`.

## 6. FEP audit log (DEF-1C-24, `baas-fep`)

The FEP is a stateless Netty spine today; this adds a minimal append-only audit store.
- **New** non-tenant `fep` schema in the shared Postgres (FEP is tenant-less) with its own datasource
  + Flyway (FEP currently has no datasource/Flyway — these deps are added). One table
  `fep.authorization_log`:

| column | type | note |
|---|---|---|
| `id` | UUID PK | |
| `received_at` | TIMESTAMPTZ | message arrival |
| `mti` | VARCHAR(4) | `0100` / `0400` |
| `stan` | VARCHAR(6) | DE11 |
| `terminal_id` | VARCHAR(8) | DE41 |
| `bin` | VARCHAR(8) | first 8 of PAN |
| `pan_last4` | VARCHAR(4) | last 4 of PAN |
| `partner_id` | UUID NULL | resolved tenant (null if BIN unrouteable) |
| `schema_name` | VARCHAR NULL | resolved schema |
| `amount_minor` | BIGINT NULL | DE4 |
| `currency` | VARCHAR(3) NULL | DE49 |
| `decision` | VARCHAR(10) NULL | card outcome |
| `response_code` | VARCHAR(2) NULL | DE39 |
| `reversal` | BOOLEAN NOT NULL | authorize vs reversal |
| `latency_ms` | INT NULL | FEP→card round-trip |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |

- **PAN safety:** stores only **BIN + last4** (a truncated PAN — PCI-DSS-permitted for retention).
  The middle digits and full DE2 are never persisted, matching the frozen PAN-safety rule.
- **Best-effort:** the FEP writes one row *after* it has the card response; a logging failure is
  caught and logged (warn) and **never** fails or alters the ISO 8583 response. The authoritative
  money records are the engine's `Transaction` + `card_auth_debit` and the card idempotency row; this
  log is a spine-side audit convenience, not the system of record.

## 7. Testing strategy

Follows the established patterns: per-module Maven, Testcontainers PostgreSQL for integration,
per-module reflection "shape tests" for cross-service contract parity, mocked clients for unit tests,
TDD (failing test first).

**Engine** — `CardAuthorizationDebitTest` (DEBITED / INSUFFICIENT for both floors / ACCOUNT_INVALID /
CURRENCY_MISMATCH / idempotent retransmit / concurrent same-card serialization);
`CardAuthorizationCreditTest` (credit + reversing txn + reversed flag / idempotent double-credit no-op
/ not-found / non-DEBITED → not located); `EngineInternalServiceAuthFilterTest` (valid pass · bad sig
/ skew / missing header → 401 · non-internal skipped · `/internal/v1/**` not 401'd by
AuthEnforcement · PartnerContext set-from-body then cleared).

**Card** — `AuthorizationDecisionServiceTest` extended (DEBITED→00, INSUFFICIENT→51,
ACCOUNT_INVALID→78, CURRENCY_MISMATCH→57, unreachable→91, linkedAccountId null→78 with no engine call,
every local decline makes no engine call, idempotency fast-path returns cached without engine call);
`ReversalServiceTest` extended (APPROVE→credit→00 + reversed; DECLINE→00 no credit; not-found→25;
unreachable-on-APPROVE→25 + reversed NOT flipped; already-reversed→00 no engine call);
`EngineClientTest` (fail-closed mapping; raw-path signer); `IssueCardRequest` validation
(`linkedAccountId` `@NotNull`; issuance rejects unknown account via mocked engine).

**FEP** — `FepAuthorizationLogTest` (one row with BIN+last4 only, asserts no full PAN; best-effort —
repo failure swallowed, ISO response unchanged); existing handler tests stay green, extended to assert
the audit write is invoked.

**Provisioning** — engine `provision()` with mocked `CardProvisioningClient` (success logs SUCCESS;
card failure throws + logs FAILED; idempotent re-run); card `/internal/v1/provision` (runs tenant
migrations into a fresh schema; idempotent on re-call).

**Cross-service parity** — each new seam (`card-debit`, `card-credit`, `provision`) gets a per-module
reflection shape test on both sides plus a signer-scheme parity test. No single test spans services
(independent Maven builds); both sides are contract-pinned. The canonical field lists and the
`METHOD|rawPath|ts|sha256Hex(body)` scheme are the load-bearing invariants.

## 8. Non-goals (out of scope for Stage 5)

- **No settlement/clearing** (DEF-1C-04) — single-message debit only.
- **No holds/reservations** — immediate debit; reversal credits back.
- **No FX / cross-currency** — account currency must equal transaction currency, else RC `57`.
- **No EMV / HSM / scheme packagers** (DEF-1C-01/02/03).
- **No distributed transaction / 2PC** — correctness via the threaded `authKey` + per-service atomic
  ops + fail-closed.
- **No message bus** for provisioning — synchronous call only.
- **No double-entry GL posting** — reuses the engine's single-`Transaction` debit/credit path, not
  balanced `JournalEntry` lines against `GlAccount`. The engine's `account/` package does not touch
  the GL today, and the GL's only entry point is `postManualJournalEntry`. Auto-posting every money
  movement to the GL is a platform-wide concern, not a card-seam one. **Registered as DEF-1C-27.**
- **No Keycloak/operator RBAC** on the internal endpoints (DEF-1C-20) — HMAC inter-service auth only.
- **Audit log is best-effort**, not a system of record.

## 9. New config keys

| Service | Key | Purpose |
|---------|-----|---------|
| baas-card | `app.engine.base-url` | card → engine debit/credit + account-existence validation |
| baas-engine | `app.card.base-url` | engine → card provisioning trigger |
| baas-fep | datasource + Flyway for the `fep` schema | authorization audit log |

(`app.internal-service.shared-secret` already exists on all three services and is reused.)

## 10. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Crash between engine debit and card row write | Engine idempotent on `authKey`; card re-call re-derives the same outcome, no double-debit |
| Concurrent same-card authorizations | Engine `findByIdForUpdate` row lock + `UNIQUE(auth_key)` — covers both concurrency and retransmit |
| Engine down on the money path | Fail closed — authorize declines `91`; reversal returns `25` (idempotent retry later) |
| Partner half-provisioned (card down at onboard) | Engine fails the whole `provision()` and logs `FAILED`; idempotent retry |
| Unit double-scaling (₦ shift) | Card is sole scaler; engine works major-units only |
| Audit DB hiccup blocks money | Audit write is best-effort; never alters the ISO response |
| GL not reflecting card activity | Acknowledged gap, tracked as DEF-1C-27 (platform-wide, not Stage 5) |

## 11. Follow-up items (post-implementation)

- **FEP deployment — datastore env required (NEW in Stage 5).** ✅ **RESOLVED (commits `6c39ff2`, `7cc5025`).**
  The FEP gained a datastore for the authorization audit log (DEF-1C-24), so it now requires
  `DATASOURCE_URL` / `DATASOURCE_USERNAME` / `DATASOURCE_PASSWORD` env vars and the non-tenant `fep` schema in
  the shared Postgres at runtime (previously the FEP was a stateless spine with no datasource).
  - `infrastructure/docker-compose.yml`: `baas-fep` block now sets the three `DATASOURCE_*` vars and
    `depends_on: postgres(service_healthy) + baas-card(service_started)`. Validated via `docker compose config`.
  - k8s: the base only had engine + ncube — `baas-fep` (and `baas-card`) had **no** manifests at all, so this
    resolution **created** them (`70/71/72-baas-fep.*`, `45/46/47-baas-card.*`) with datasource env, NetworkPolicy
    mesh, PDBs, and a LoadBalancer for the ISO 8583 TCP port. Flyway auto-creates the `fep` schema
    (`create-schemas: true`, `default-schema: fep`) — no DB init script needed. Validated via
    `kubectl kustomize overlays/{dev,staging,prod}`.
  - Also fixed a **pre-existing** latent k8s bug found en route: every `baas-*` Service fronts pods on port 80,
    so the app-side inter-service URL defaults (`:8081`/`:8082`) connection-refuse; engine's `NCUBE_BASE_URL`
    override was missing and would have failed engine→ncube in k8s. All inter-service URLs now pinned to `:80`.
  - **Partner→card Ingress routing:** added in the same pass. card's partner API (`/baas/v1/{cards,card-products,
    bins}`) is carved out of engine's `/baas/v1` namespace via more-specific Ingress prefixes (longest-prefix
    match) + an `allow-ingress-to-card` NetworkPolicy rule. Auth stays at the service (PartnerContextFilter).
  - **CI images already exist** (corrected — an earlier draft of this note wrongly said they didn't):
    `.github/workflows/baas-card-ci.yml` and `baas-fep-ci.yml` build + push `ghcr.io/<owner>/baas-{card,fep}:<sha>`
    on push to main, so overlays can resolve a real SHA today via `kustomize edit set image`. No follow-up needed.
  (Tests are unaffected — they use H2.)
- **Figma boards.** The Service Architecture + data-flow boards now understate reality (the engine↔card
  money seam over `/internal/v1/{card-debit,card-credit,account-lookup}`, the engine→card provisioning
  trigger, and the FEP datastore) — regenerate when convenient.
