# NubBank BaaS — Deferred Items Registry

Structure: `ID | Item | Why deferred | Earliest phase | Source`. Append new deferrals here.

| ID | Item | Why deferred | Earliest phase | Source |
|---|---|---|---|---|
| DEF-1C-01 | EMV ARQC/ARPC validation (TDES + SM4) | No live card scheme in 1C | Phase 2 | spec §6.7 |
| DEF-1C-02 | HSM hardware adapter (Thales) | Software stub sufficient | Phase 2 | spec §6.7 |
| DEF-1C-03 | Scheme-specific jPOS packagers + private DEs | No certification in 1C | Phase 2 | spec §6.7 |
| DEF-1C-04 | Settlement/advice MTIs | No settlement in 1C | Phase 2 | spec §6.7 |
| DEF-1C-05 | Tokenization vault (DPAN) | No tokenized creds in 1C | Phase 2 | spec §6.6 |
| DEF-1C-06 | Interchange, 3DS/ACS, chargeback | Scheme-grade card ops | Phase 2 | spec §3 |
| DEF-1C-07 | Card personalization bureau (CDP) | No physical production | Phase 2 | spec §3 |
| DEF-1C-08 | DB-level orphaned-grant guard for user_roles.user_id | Reconciliation sweep covers it | revisit | spec §6.2.4 |
| DEF-1C-09 | Keycloak JWKS rotation/invalidation | Default decoder cache acceptable | Phase 2 | spec §6.1 |
| DEF-1C-10 | Live external export delivery (SFTP) | Stub-mode in 1C | Phase 2 | spec §6.5 |
| DEF-1C-11 | ✅ **RESOLVED (2026-06-07).** Frontend component-library choice = **shadcn/ui** (Radix + Tailwind, copy-in; tables via TanStack Table). Decided at Track-Backoffice start. | — | Phase 1C | spec §7.1 → `2026-06-07-baas-backoffice-design.md` §3 |
| DEF-1C-12 | Routing-by-annotation vs by-chain | Start by-chain | Phase 1C/2 | spec §6.3 |
| DEF-1C-13 | Maker-checker UI beyond engine support | Engine support is the constraint | Phase 3 | spec §13 |
| DEF-1C-14 | TRADE_FINANCE_OFFICER role | No corporate partner in 1C | on corporate onboard | spec §14 |
| DEF-1C-15 | Granular RBAC for HMAC partner-login users | First-party creds get full tenant authority in 1C | Phase 2 | Foundation Task 5 |
| DEF-1C-16 | @PreAuthorize rollout across all controllers | Demonstrated on CustomerController; rolled out per module | Phase 1C (per track) | Foundation Task 6 |
| DEF-1C-17 | Live Keycloak directory + cross-schema reconciliation sweep | Stub directory in 1C | Phase 2 | Foundation Task 8 |
| DEF-1C-18 | Authority caching (per-request DB hit today) | Acceptable load in 1C | Phase 2 | Foundation Task 5 |
| DEF-1C-19 | Restrict non-health actuator endpoints (info/metrics/env currently publicly reachable) | Pre-existing dev-friendly posture; harden before prod | Phase 1C (Custodian/infra) | Foundation Task 9 review |
| DEF-1C-20 | Operator-JWT/Keycloak RBAC on baas-card endpoints | First-party auth only in 1C | Phase 1C (Stage 4 — Backoffice) | Track-Card |
| DEF-1C-21 | Decouple card from engine's public partner tables (own partner mirror) | Shared DB acceptable in 1C | Phase 2 | Track-Card |
| DEF-1C-22 | ✅ **CLOSED (Session 12 / Stage 5).** Cross-service tenant provisioning trigger (engine→card schema objects) | Engine `TenantProvisioningService` now calls card `POST /internal/v1/provision` after its own migrations; card failure fails the whole provisioning. | Phase 1C (Stage 5) | Track-Card |
| DEF-1C-23 | ✅ **CLOSED (Session 12 / Stage 5).** Card authorization balance check (real, via baas-engine) | An approved authorization debits the engine account via `POST /internal/v1/card-debit` (atomic, idempotent on `auth_key`, fail-closed RC 91). The `@Transactional`-before-context pitfall was respected: the internal controller sets `PartnerContext` from the body, then calls the `@Transactional` service. | Phase 2 | Track-Card |
| DEF-1C-24 | ✅ **CLOSED (Session 12 / Stage 5).** FEP authorization-log persistence (auth audit trail) | FEP gained a datastore (`fep.authorization_log`); `AuthorizationAuditService` writes every authorize/reversal decision best-effort (BIN+last4 only, never the full PAN). Postgres in prod; H2 (PostgreSQL mode) in tests. | Phase 2 | Track-FEP |
| DEF-1C-25 | ✅ **CLOSED (Session 12 / Stage 5).** Reversal (0400) fund reversal — credit cardholder account | Located in Session 11; Stage 5 added the fund credit: `ReversalService` calls engine `POST /internal/v1/card-credit` (idempotent on `auth_key`) to credit the account back, only when the original was an APPROVE-debit. Engine-unreachable → RC 25 without flipping `reversed` (terminal retries; credit is idempotent). | Phase 2 | Track-FEP / Sessions 11–12 |
| DEF-1C-26 | Card-BIN-change cache invalidation push (vs 5-min Caffeine TTL only) | 5-min TTL acceptable in 1C | Phase 2 | Track-FEP |
| DEF-1C-27 | Automatic GL double-entry posting for account money movements (platform-wide) | Engine's `account/` package uses a single-`Transaction` debit/credit path and does not post balanced `JournalEntry` lines against `GlAccount`; the GL's only entry point today is `postManualJournalEntry`. Auto-posting every money movement (deposit, withdrawal, transfer, card) to the GL is a uniform cross-cutting concern, not a card-seam one — doing it for the Stage 5 card path alone would book card money differently from every other debit. | Phase 2+ (platform-wide) | Stage 5 design (2026-06-04) |
| DEF-1C-28 | ✅ **CLOSED (Session 15).** `GET /baas/v1/operators/me` (operator identity + authorities) | Engine endpoint returns operatorId/authMode/partner/tier/environment/roles/authorities. PKCE provider now fetches authorities from `/me` (Keycloak tokens don't carry them); token-claim parse is fallback-only. Dev mode unchanged. | Phase 1C | baas-backoffice Foundation (F5) |
| DEF-1C-29 | ✅ **CLOSED (Session 15).** Dashboard aggregate endpoint (deposits total, KYC-pending count, cards issued) | `GET /baas/v1/dashboard/summary` (partner-scoped) + `baas-card POST /internal/v1/stats` for the cards tile (graceful null on card outage). Backoffice tiles wired to real values. | Phase 1C | baas-backoffice Foundation (Task 15) |
| DEF-1C-30 | Customer identity verification via `baas-ncube` (BVN/NIN → KycLevel) | The Customers track *captures* BVN/NIN and shows them masked, but does NOT verify them. Real verification against NIBSS/CBN through `baas-ncube`, `KycLevel` progression driven by the verification result, and retry/failure handling are a dedicated Identity/KYC-verification track. BVN/NIN are intentionally not editable via `PUT /customers/{id}` for the same reason. | Phase 2 (Identity track) | Customers track design (`docs/superpowers/specs/2026-06-10-customers-track-design.md`) |
| DEF-1C-31 | Map `ObjectOptimisticLockingFailureException` → HTTP 409 (platform-wide) | Concurrent writes to the same row (e.g. two operators transitioning/editing the same customer) currently surface Hibernate's optimistic-lock failure as a 500 via the shared catch-all `@ExceptionHandler(Exception.class)`. The state stays correct (one writer wins) but a 409 "Concurrent modification — please retry" is the right HTTP semantic. This is a cross-cutting change to the shared `GlobalExceptionHandler`, not customer-specific, so it is deferred out of the Customers track. | Phase 2 (platform hardening) | Customers track — surfaced in Task 5 code review |

## Hard prerequisites (not optional deferrals — must accompany the named work)

- **DEF-1C-17**: When the `live-keycloak` Spring profile is activated, a `@Profile("live-keycloak")` implementation of `KeycloakUserDirectory` MUST be registered, or application context startup fails with `NoSuchBeanDefinitionException` (`OperatorGrantReconciliationJob` requires the bean). The stub `StubKeycloakUserDirectory` is `@Profile("!live-keycloak")` and is excluded when the live profile is on.
