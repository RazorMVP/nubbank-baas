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
| DEF-1C-11 | Frontend component-library choice | Decide at Track-Backoffice start | Phase 1C | spec §7.1 |
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
| DEF-1C-22 | Cross-service tenant provisioning trigger (engine→card schema objects) | Card tests self-provision in 1C | Phase 1C (Stage 5) | Track-Card |
| DEF-1C-23 | Card authorization balance check (real, via baas-engine) | Stub always-sufficient in 1C | Phase 2 | Track-Card |
| DEF-1C-24 | FEP authorization-log persistence (auth audit trail) | FEP is a stateless spine in 1C (no DB) | Phase 2 | Track-FEP |
| DEF-1C-25 | Reversal (0400) real processing — match original + reverse | Stub approves (`00`) in 1C | Phase 2 | Track-FEP |
| DEF-1C-26 | Card-BIN-change cache invalidation push (vs 5-min Caffeine TTL only) | 5-min TTL acceptable in 1C | Phase 2 | Track-FEP |

## Hard prerequisites (not optional deferrals — must accompany the named work)

- **DEF-1C-17**: When the `live-keycloak` Spring profile is activated, a `@Profile("live-keycloak")` implementation of `KeycloakUserDirectory` MUST be registered, or application context startup fails with `NoSuchBeanDefinitionException` (`OperatorGrantReconciliationJob` requires the bean). The stub `StubKeycloakUserDirectory` is `@Profile("!live-keycloak")` and is excluded when the live profile is on.
