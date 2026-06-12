# NubBank BaaS Skill

Use this skill whenever working on the NubBank BaaS platform (`nubbank-baas/` repository).

---

## ⛔ SESSION COMPLETION GATE — READ BEFORE SAYING "DONE"

**You MUST NOT close a session, summarise completion, or push to GitHub until every item below is checked. This is a hard stop, not a suggestion.**

### Mandatory End-of-Session Checklist

- [ ] **1. Build verification (per service touched)** — run the test suite of **every** service whose files changed this session. A service is exempt **only** if it had zero file changes. Find touched services with `git diff --name-only main...HEAD | sed 's#/.*##' | sort -u`.

  | Service | Stack | Verify command |
  |---------|-------|----------------|
  | `baas-engine` | Java/Spring | `cd ~/nubbank-baas/baas-engine && ./mvnw test -q` |
  | `baas-card` | Java/Spring | `cd ~/nubbank-baas/baas-card && ./mvnw test -q` |
  | `baas-fep` | Java/Spring | `cd ~/nubbank-baas/baas-fep && ./mvnw test -q` |
  | `baas-ncube` | Java/Spring | `cd ~/nubbank-baas/baas-ncube && ./mvnw test -q` |
  | `baas-backoffice` | React/Vite | `cd ~/nubbank-baas/baas-backoffice && npm run typecheck && npm test` |
  | `baas-portal` *(when built)* | React/Vite | `cd ~/nubbank-baas/baas-portal && npm run typecheck && npm test` |

  All suites for all touched services must be green before any commit.

- [ ] **2. `baas-log.md`** — New session entry added at the **top** of Change History. Must include:
  - Session number, date, one-line summary + final commit SHA
  - New/Updated Files table
  - Key Decisions (architectural choices, gotchas discovered)
  - Build Verification (`Tests run: N, Failures: 0, BUILD SUCCESS`)
  - **Confirmed Platform Versions** block (SHA from `git log --oneline -1 -- baas-engine/`)

- [ ] **3. `CLAUDE.md`** — Updated:
  - Confirmed Platform Versions SHA (must match last commit)
  - Module Catalogue — new modules ✅, pending modules current
  - Any new gotchas in the Known Gotchas table

- [ ] **4. Service docs (per service touched)** — **every** service worked on at any point in the session must have its reference doc updated. Not just `baas-engine` — `baas-backoffice`, `baas-card`, `baas-fep`, and `baas-ncube` each carry their own doc surface. A service is exempt **only** if it had zero file changes this session.

  | Service | Doc artifact | What to update |
  |---------|--------------|----------------|
  | `baas-engine` | `docs/api-reference.html` | Every new/changed REST endpoint. Find with `git diff main...HEAD --name-only \| grep '\.java$'` then grep `@(Get\|Post\|Put\|Delete\|Patch)Mapping`. |
  | `baas-card` | `docs/api-reference.html` (Card section) | New/changed card REST endpoints + any internal `/internal/v1/*` contract changes. |
  | `baas-ncube` | `docs/api-reference.html` (Ncube/CBN section) | New/changed CBN-adapter endpoints + vendor media-type/format changes. |
  | `baas-fep` | `docs/fep-iso8583-reference.md` *(create if absent)* | New/changed ISO 8583 MTIs, DEs, response codes, reversal/auth flow. FEP is TCP, not REST — it does **not** belong in `api-reference.html`. |
  | `baas-backoffice` | `docs/backoffice-operations.md` *(create if absent)* | New screens/routes, RBAC permission codes consumed, env vars, auth modes (dev vs PKCE). The design spec under `docs/superpowers/specs/` stays the canonical *spec*; this is the living *operations* doc. |

  Cross-cutting: if any service touched Open Banking / KYC / consent / payment surface, also do item 5 (CBN gap analysis).

- [ ] **5. CBN compliance gap analysis** — If any Open Banking, KYC, consent, or payment feature changed:
  - Update `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md`
  - Move items from ❌ to ⚠️ or ✅ as appropriate

- [ ] **6. Figma architecture/flow diagrams** — If service architecture or data flows changed, flag which boards need updating:
  - [Service Architecture](https://www.figma.com/board/PRACgc6BXsGVEL7ZhB866A)
  - [Multi-Tenancy Flow](https://www.figma.com/board/TR1AYhx9Pcmd5y5grxMv8v)
  - [Partner Provisioning Flow](https://www.figma.com/board/qHD6cSCRTQHPbkmavHtoxw)
  - [CBN Compliance Roadmap](https://www.figma.com/board/5KpYYAtiukv7G6o3LyjsVr)
  - Note in `baas-log.md` which boards were regenerated

- [ ] **7. Figma backoffice module designs (EDITABLE — never a screenshot)** — If ANY `baas-backoffice` frontend screen was built or changed this session, the corresponding screen(s) **MUST** be added or updated in the **[NubBank BaaS — Backoffice](https://www.figma.com/design/gEDnLrLD4UrChcND0yCdZ9/NubBank-BaaS-%E2%80%94-Backoffice?node-id=0-1)** Figma **design** file (fileKey `gEDnLrLD4UrChcND0yCdZ9`) as **proper, natively-editable Figma designs** — real frames + auto-layout + components + selectable text + design-token styles, assembled with the Figma MCP (`use_figma`; load the `figma-generate-design` skill first, and `figma-use` for the API rules).
  - **A pasted screenshot / PNG / image-fill is NOT acceptable.** The design must be editable in Figma: movable frames, selectable/editable text, reusable components — so a designer can iterate on it. If you can only produce a raster image, the item is **not** satisfied.
  - **One frame per screen and per significant state** — e.g. for the Customers module: Customers list, Customer detail, create/edit modal, KYC action modal, empty/error states — grouped under a page or section named for the module.
  - **Reuse the backoffice design system** (shadcn/Tailwind tokens, the same spacing/colour/typography the running app uses) — match the built UI; do not invent a new visual language. Where a design-system component already exists in the Figma library, instance it rather than redrawing primitives.
  - **Record it** in `baas-log.md` (which module frames were added/updated + the Figma node link) and tick this box only when the editable frames exist in the file above.
  - Exempt **only** if no `baas-backoffice/src/**` screen/component changed this session.

- [ ] **8. `/baas` skill update** — If a Phase or sub-plan completed: mark ✅ in Phase Build Order below

- [ ] **9. Deployment-agnostic check** — If a new service was added:
  - [ ] `Dockerfile` committed and tested
  - [ ] `nginx.conf` committed
  - [ ] `infrastructure/docker-compose.yml` entry added
  - [ ] CI workflow committed (`.github/workflows/{service}-ci.yml`)

- [ ] **10. Commit and push**
  ```bash
  # Include every per-service doc touched this session (item 4 matrix):
  #   docs/api-reference.html, docs/fep-iso8583-reference.md,
  #   docs/backoffice-operations.md, docs/regulatory/
  git add CLAUDE.md baas-log.md docs/ .claude/skills/baas/SKILL.md
  git commit -m "docs(baas-log+claude): Session N — <summary>

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  git push origin main
  ```

  **Pre-push hook gate:**
  - Blocks if `Confirmed Platform Versions` is missing from either `baas-log.md` or `CLAUDE.md`.

  **Hook setup (one-time per clone):**
  ```bash
  cd ~/nubbank-baas
  git config core.hooksPath .githooks
  ```
  `.githooks/pre-push` is committed to the repo; `core.hooksPath` is a per-clone pointer Git uses to find hooks. Bypass with `git push --no-verify` only after writing a one-line reason in `baas-log.md` under the current session entry.

### Rationalisation Traps — These Are Not Valid Reasons to Skip

| Thought | Why it's wrong |
|---------|---------------|
| "The frontend didn't touch the backend" | `CLAUDE.md` and `baas-log.md` still need updating for every session |
| "I'll do docs next session" | The next session starts cold. Missing docs will be missed again. |
| "It was just a small fix" | Every session gets a log entry, no exceptions |
| "Tests passed locally, no need to re-run" | Run immediately before committing — local state can drift |
| "Vercel handles the deploy, Dockerfile is redundant" | Vercel is one target. Dockerfile is the portability contract. |
| "Figma diagrams are optional" | They are the visual spec shared with stakeholders. Stale diagrams create confusion. |
| "A screenshot of the screen is enough for Figma" | Gate item 7 requires an **editable** design (frames/components/selectable text), not a raster image. A screenshot can't be iterated on by a designer, can't reuse design-system components, and drifts silently. If you can only export a PNG, the item is **not** done. |
| "The frontend module works, the Figma can wait" | Every `baas-backoffice` screen built/changed gets its editable Figma frame the same session (gate item 7). "Next session" starts cold and the design debt compounds per module. |
| "CBN gap analysis was updated last session" | Last session's analysis doesn't cover this session's changes. |
| "The API docs can wait until we have more endpoints" | One missing endpoint breaks partner integrations silently. |
| "Only `baas-engine` has docs to update" | Every service has its own doc surface — see gate item 4's matrix (`baas-backoffice`, `baas-card`, `baas-fep`, `baas-ncube`, `baas-engine`). Touching **any** of them triggers its doc update. FEP docs live in `fep-iso8583-reference.md`, backoffice in `backoffice-operations.md`. |
| "It's a frontend service, frontends don't have API docs" | `baas-backoffice` carries an operations doc (routes, RBAC codes, env, auth modes). "No REST endpoints" ≠ "no docs". |
| "It's one feature, one branch is simpler" | A feature spanning services splits into one PR per service — see § Branch & PR Discipline. Bundling couples their merge/deploy. |

---

## 🌿 Branch & PR Discipline — One Service Per PR

Each service is an **independent deployable** — its own CI workflow, Dockerfile, k8s manifests, and deploy cadence. Work on a service goes on **its own branch and its own PR**. **Never bundle changes to multiple services onto one branch/PR.** The five services share a monorepo, not a release.

| Rule | Detail |
|------|--------|
| **One service per PR** | A PR's *code* changes touch exactly ONE of `baas-engine`, `baas-card`, `baas-fep`, `baas-ncube`, `baas-backoffice` (+ future `baas-portal`, `baas-docs`). |
| **Split cross-service features** | A feature spanning services (e.g. a frontend that consumes a new backend endpoint) → **one PR per service, each independently mergeable**. The dependent side must degrade gracefully if the other isn't deployed yet (no hard merge-order dependency). |
| **Zero-overlap docs** | Keep the split PRs from touching the same files: consolidate shared ledgers (`baas-log.md`, `CLAUDE.md`, `docs/deferred-items.md`) into **one** of the PRs; each service's API/ops doc (`docs/api-reference.html`, `docs/backoffice-operations.md`, `docs/fep-iso8583-reference.md`) rides with **that service's** PR. Zero file overlap ⇒ merge in any order, no conflict. |
| **Branch naming** | `feat/<service>-<short-desc>` — e.g. `feat/baas-engine-card-operations-api`, `feat/baas-backoffice-foundation`. |

**Pre-merge check (do before opening or merging a PR):** run `git diff --name-only main...HEAD | sed 's#/.*##' | sort -u`. The service directories listed must be exactly one (`baas-*`), plus shared non-service paths (`docs/`, `infrastructure/`, `.github/`, `.claude/`, `CLAUDE.md`, `baas-log.md`, `assets/`). More than one `baas-*` service ⇒ **split before merge**.

**Worked example (Session 15, DEF-1C-28/29):** a cross-service feature (engine + card endpoints + backoffice wiring) was split into PR #27 `feat/baas-engine-card-operations-api` (`baas-engine` + `baas-card` + `api-reference.html`) and PR #26 `feat/baas-backoffice-foundation` (`baas-backoffice` + ledgers) — **zero file overlap**, mergeable in any order.

**Why:** bundling couples the services' merge and deploy. A frontend ready to ship shouldn't wait on a backend PR's review (or vice-versa). Independence at the **git level** — not just conceptually — is the goal.

---

## 🎯 Expert Review — On Request

The Expert Review is an **opt-in second-pass tool**, not a gate. Invoke it when you (or the user) want a sanity check on a non-trivial decision. There is no per-session, per-commit, or per-controller requirement to produce one — forced cadence creates churn without insight.

When invoked, produce the block in § Review Format using the persona in § The Expert Persona.

### The Expert Persona

A software engineer with **20+ years building core banking applications for leading banks across the world** (US, UK, Europe, Africa, Asia). They have lived through:

- Production incidents at 3am during financial year-ends
- Mainframe COBOL → Java microservices migrations
- Regulator audits across jurisdictions (FCA, CBN, BSP, FRB, MAS)
- Card scheme certifications (Visa, Mastercard, Verve)
- ISO 8583, ISO 20022, FAPI 2.0, Open Banking UK, PSD2
- Multi-tenant SaaS-for-banks projects with real money flowing

They have the scars. They do not pad. They do not give participation trophies. They call out only what genuinely matters.

### Review Format

```markdown
---

### 🎯 Expert Review (20+ yrs core banking)

**What you got right**
- [Only the genuinely solid parts — no padding. If nothing is truly solid, say so plainly.]

**What you oversimplified or got wrong**
- [Specific oversights: missing failure modes, transaction-boundary errors, idempotency gaps, regulator-facing gaps, security holes, missed concurrency cases, reconciliation blind spots, audit-trail omissions. Name files, fields, edge cases. Generalities are useless.]

**Best practice recommendation for *this* context**
- [What the expert would actually do given NubBank BaaS's stack — Java 21 / Spring Boot 3.5 / Hibernate SCHEMA multi-tenancy / Partner JWT + API key / Redis rate limiting / CBN regulatory environment / current Phase position. Not generic advice — context-specific and actionable.]

---
```

### When You Should Consider Invoking It

The Expert Review is most valuable before committing to a decision that is **expensive to reverse**:

- A multi-file architectural decision before implementing
- A new state machine touching money movement (payment, reversal, settlement, dispute)
- A schema change that touches multi-tenancy, audit, or consent
- Anything that touches CBN OBR, FAPI 2.0, ISO 20022, BVN/NIN
- Before opening a PR with significant security or payment surface
- At a phase boundary (see § Phase-Gate Review below — this is the primary place the review is exercised)

It is **not** required on every session, every commit, or every controller change.

### Trigger Word

When the user types `expert review`, `expert critique`, `second pass`, or `review your last answer`, immediately produce the Expert Review block for the most recent substantive answer.

### Anti-Patterns the Expert Avoids

- **"This is great, but..."** — if it's truly great, name what specifically. If there is a "but", lead with it.
- **Theoretical risk dumps** — only call out what's likely in *this* codebase, *this* phase. Don't list every CWE under the sun.
- **Rewrite-first reflex** — recommend small targeted fixes over architectural overhauls when the fix is local.
- **"Best practice" without specifics** — name the practice, cite where it came from (Mifos, FAPI 2.0 §x, CBN §y, ISO 20022 message type), and explain why it applies here.
- **Yes-man tone** — the entire purpose is to catch oversimplification. If the original answer was wrong, say so directly.
- **Hindsight rephrasing** — don't simply repeat what was already said in the main answer dressed as a critique. The review must add new information or sharpen a real risk.
- **Out-of-phase critique** — if the gap requires Phase N+1 work that is not yet scoped, file it as `[deferred-to-phase-N+1]` in the phase-gate review, not as an open in-flight critique.

---

## Product Context

NubBank BaaS is a **Banking as a Service** platform. It is a completely separate product from NubBank SaaS (`CoreBanking/`). Do NOT touch or reference anything in `CoreBanking/` when working on this project.

### Three Commercial Models

| Model | Customer | Isolation | Regulatory |
|-------|----------|-----------|-----------|
| **A** | Fintech / Neobank | Schema isolation | Under NubBank licence |
| **B** | Enterprise embedded finance | Schema isolation | Lighter compliance |
| **C** | Licensed bank | Database isolation | Full partner autonomy |

### Repository

```
nubbank-baas/
├── CLAUDE.md                    ← Body of knowledge (read at session start)
├── baas-log.md                  ← Session change log (update at session end)
├── baas-engine/                 ← Spring Boot 3.5 / Java 21 (port 8080)
├── baas-card/                   ← Card service (port 8081) — planned
├── baas-ncube/                  ← CBN/Ncube adapter (port 8082) — planned
├── baas-portal/                 ← React 19 developer portal (port 3000) — planned
├── baas-backoffice/             ← React 19 operations backoffice (port 3001) — planned
├── baas-docs/                   ← Docusaurus 3 docs (port 3002) — planned
└── infrastructure/              ← Docker + K8s — planned
```

---

## Key References

| File | Read When |
|------|-----------|
| `CLAUDE.md` | Start of every session — full architecture, module catalogue, gotchas |
| `baas-log.md` | Understanding what was built and when |
| `docs/superpowers/plans/2026-04-27-nubbank-baas-phase1a-engine.md` | Executing Phase 1A baas-engine tasks |
| PRD on Confluence | https://akinwalenubeero.atlassian.net/wiki/spaces/NCBP/pages/349208578 |

---

## Multi-Tenancy — The Critical Pattern

Every partner gets a dedicated PostgreSQL schema. Hibernate SCHEMA strategy routes queries automatically via `SET search_path`.

- **Public schema entities** (PartnerOrganization, PartnerUser, PartnerApiKey, VirtualAccountPool) → MUST have `@Table(schema = "public")`
- **Tenant schema entities** (Customer, Account, Transaction, Payment) → NO schema annotation; Hibernate routes them
- **PartnerContextFilter** → always clears ThreadLocal in `finally` block
- **TenantProvisioningService** → creates `partner_{uuid}` + `sandbox_{uuid}` schemas + runs Flyway on both

---

## Phase Build Order

### Phase 1 — Foundation (Active)

| Sub-plan | Deliverable | Status |
|----------|-------------|--------|
| 1A | `baas-engine` — multi-tenancy + core APIs | ✅ Complete (Session 1, commit `c6c5e47`) |
| 1A-ext | `baas-engine` — 29 banking modules + 12 critical security fixes | ✅ Complete (Session 4, squash merge `5adeb10`, 84 tests) |
| 1B | `baas-ncube` — CBN format + BVN/NIN | ✅ Complete (Session 2, commit `97544ce`) |
| 1F-0 | Cross-cutting security baseline (1B C1, C2, C5, I1, I3, I7) | ✅ Complete (Session 5, branch `feature/phase1f-0-cross-cutting-security` HEAD `d8b1802`, 97 engine + 49 ncube tests) — adds `AuthEnforcementFilter`, body-signed HMAC inter-service auth, `StubModeGuard`, `X-NubBank-Stubbed` header, CBN vendor media type, `PiiMaskingConverter` (Logback) |
| 1C / D7 | `baas-fep` — stateless ISO 8583 front-end processor (Netty + jPOS + MTI router + BIN routing + auth flow) | ✅ Complete (Session 9, branch `feature/phase1c-fep` HEAD `29400fc`, 46 tests; Card client mocked — live wiring Stage 5) |
| 1C | `baas-backoffice` — React/Vite operations portal | 🟡 **Foundation ✅** (Session 14/15, `281739a`) — app skeleton, hybrid auth, RBAC gating, dashboard, CI/Docker/k8s. **Customers — first domain track ✅** (Session 16, frontend PR `feat/baas-backoffice-customers` `373ebcd` + engine PR #28 `feat/baas-engine-customer-lifecycle`) — list/detail/create/edit, masked-PII, KYC state machine + history. Remaining domain tracks (Accounts, Loans, Payments, Teller, Charges, Accounting, Reports, Compliance, Offices/Staff, Roles, Audit) pending. |
| 1D | `baas-portal` — React developer portal | ⬜ Not started |
| 1E | Infrastructure — Docker + k8s + CI/CD | ✅ Complete (Session 4) — `Dockerfile` for engine + ncube, `infrastructure/docker-compose.yml`, `infrastructure/k8s/` (vanilla manifests), `.github/workflows/baas-{engine,ncube}-ci.yml` |
| 1F-E | Infrastructure hardening — 22 tasks, 28 findings (6C + 13I + 9m) | ✅ Complete (Session 6, merge commit `ac5687b`, tag `phase1f-e-merged`, PR #5, 22/22 tasks, 40 commits); includes Kustomize tree restructure, NetworkPolicy + PDB components, image digest pins, JVM hardening, compose hardening, GHCR CI + SBOM + SLSA L1, Dependabot, CODEOWNERS, HPA tuning, SecurityConfig `/actuator/health/**` fix |

### Phase 2 — Model A (Fintech/Neobank)

KYC delegation + BVN/NIN live via Ncube + NIP routing + Ncube participant registration

### Phase 3 — Model B (Embedded Finance)

Virtual account pool + loan APIs + metering + billing engine

### Phase 4 — Model C (Licensed Bank)

DB isolation provisioner + Platform Admin screens + white-label capability

---

## Phase-Gate Review

The primary place the Expert Review (§ Expert Review — On Request) is exercised. Run a structured review against the **whole phase's deliverables as a unit** — not session-by-session — at the points listed below.

### When This Fires

- A sub-plan reaches ✅ Complete in the § Phase Build Order table (1A done, 1F-0 done, 1F-E done, etc.)
- A pre-prod milestone is reached
- A real production incident traces back to a decision made during a specific phase

### Procedure

1. Enumerate what landed: `git log --oneline <phase-start-sha>..<phase-end-sha> -- baas-engine/ baas-ncube/`
2. Invoke the Expert Review persona against the **whole phase as a unit** — not per file, not per commit.
3. Capture the review in `docs/phase-gate-reviews.md` with one row per phase. Each row should carry an explicit closure state:
   - `[resolved] <sha>` — fixed inside the phase or the next one
   - `[deferred-to-phase-N]` — scoped to a later phase, not in-flight
   - `[accepted-risk] <one-line reason>` — known limitation accepted for this phase
   - `[wontfix] <one-line reason>` — closed; not pursuing
   - `↑ Promoted <sha>` — lifted into `CLAUDE.md` § Known Gotchas
4. Surface items into `CLAUDE.md` § Known Gotchas only when they're patterns future sessions need to remember.

### Why Phase-Gate Not Per-Session

A 20-year expert engineer evaluating an in-flight session always finds something to flag — there is no natural stop condition. Per-session reviews compound into a backlog you can never empty. Phase-gate review gives bounded scope, real closure, and an explicit "deferred to later phase" lifecycle state that closes the row without pretending the work is done.

There is no hook, CI check, or required artifact enforcing this. It is governance, not enforcement.

---

## Session Log Entry Template

```markdown
### Session N — YYYY-MM-DD
**One-line summary (commit SHA).**

#### New/Updated Files
| File | Change |
|------|--------|
| ... | ... |

#### Key Decisions

#### Build Verification
Tests run: X, Failures: 0, BUILD SUCCESS

#### Confirmed Platform Versions

**BaaS Engine (`baas-engine/`):**
| Component | Version | Git ref |
|-----------|---------|---------|
| Spring Boot | 3.5.0 | `<sha>` |
| Java | 21 | `<sha>` |
| Nimbus JOSE+JWT | 9.37.3 | `<sha>` |
| Last git commit | `<sha>` | Session N — summary |
```
