# NubBank BaaS Skill

Use this skill whenever working on the NubBank BaaS platform (`nubbank-baas/` repository).

---

## ⛔ SESSION COMPLETION GATE — READ BEFORE SAYING "DONE"

**You MUST NOT close a session, summarise completion, or push to GitHub until every item below is checked. This is a hard stop, not a suggestion.**

### Mandatory End-of-Session Checklist

- [ ] **1. Build verification** — `cd ~/nubbank-baas/baas-engine && ./mvnw test -q` — all tests must pass before any commit. Only sessions that touched zero Java files may skip.

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

- [ ] **4. API docs** — If ANY `baas-engine` controller file was touched:
  - `git diff HEAD~1 HEAD --name-only | grep -E '\.java$'` to find changed files
  - Grep for `@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@PatchMapping`
  - Update `docs/api-reference.html` for every new or changed endpoint
  - Zero controller files touched = may skip

- [ ] **5. CBN compliance gap analysis** — If any Open Banking, KYC, consent, or payment feature changed:
  - Update `docs/regulatory/CBN-Open-Banking-Compliance-Gap-Analysis.md`
  - Move items from ❌ to ⚠️ or ✅ as appropriate

- [ ] **6. Figma diagrams** — If service architecture or data flows changed, flag which boards need updating:
  - [Service Architecture](https://www.figma.com/board/PRACgc6BXsGVEL7ZhB866A)
  - [Multi-Tenancy Flow](https://www.figma.com/board/TR1AYhx9Pcmd5y5grxMv8v)
  - [Partner Provisioning Flow](https://www.figma.com/board/qHD6cSCRTQHPbkmavHtoxw)
  - [CBN Compliance Roadmap](https://www.figma.com/board/5KpYYAtiukv7G6o3LyjsVr)
  - Note in `baas-log.md` which boards were regenerated

- [ ] **7. `/baas` skill update** — If a Phase or sub-plan completed: mark ✅ in Phase Build Order below

- [ ] **8. Deployment-agnostic check** — If a new service was added:
  - [ ] `Dockerfile` committed and tested
  - [ ] `nginx.conf` committed
  - [ ] `infrastructure/docker-compose.yml` entry added
  - [ ] CI workflow committed (`.github/workflows/{service}-ci.yml`)

- [ ] **9. Commit and push**
  ```bash
  git add CLAUDE.md baas-log.md docs/regulatory/ .claude/skills/baas/SKILL.md
  git commit -m "docs(baas-log+claude): Session N — <summary>

  Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
  git push origin main
  ```
  The pre-push hook blocks if `Confirmed Platform Versions` is missing from either `baas-log.md` or `CLAUDE.md`.

### Rationalisation Traps — These Are Not Valid Reasons to Skip

| Thought | Why it's wrong |
|---------|---------------|
| "The frontend didn't touch the backend" | `CLAUDE.md` and `baas-log.md` still need updating for every session |
| "I'll do docs next session" | The next session starts cold. Missing docs will be missed again. |
| "It was just a small fix" | Every session gets a log entry, no exceptions |
| "Tests passed locally, no need to re-run" | Run immediately before committing — local state can drift |
| "Vercel handles the deploy, Dockerfile is redundant" | Vercel is one target. Dockerfile is the portability contract. |
| "Figma diagrams are optional" | They are the visual spec shared with stakeholders. Stale diagrams create confusion. |
| "CBN gap analysis was updated last session" | Last session's analysis doesn't cover this session's changes. |
| "The API docs can wait until we have more endpoints" | One missing endpoint breaks partner integrations silently. |

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
| 1C | `baas-backoffice` — React/Vite operations portal | ⬜ Next — start now |
| 1D | `baas-portal` — React developer portal | ⬜ Not started |
| 1E | Infrastructure — Docker + k8s + CI/CD | ✅ Complete (Session 4) — `Dockerfile` for engine + ncube, `infrastructure/docker-compose.yml`, `infrastructure/k8s/` (vanilla manifests), `.github/workflows/baas-{engine,ncube}-ci.yml` |

### Phase 2 — Model A (Fintech/Neobank)

KYC delegation + BVN/NIN live via Ncube + NIP routing + Ncube participant registration

### Phase 3 — Model B (Embedded Finance)

Virtual account pool + loan APIs + metering + billing engine

### Phase 4 — Model C (Licensed Bank)

DB isolation provisioner + Platform Admin screens + white-label capability

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
