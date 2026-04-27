# NubBank BaaS Skill

Use this skill whenever working on the NubBank BaaS platform (`nubbank-baas/` repository).

---

## ⛔ SESSION COMPLETION GATE — READ BEFORE SAYING "DONE"

**You MUST NOT close a session, summarise completion, or push to GitHub until every item below is checked.**

### Mandatory End-of-Session Checklist

- [ ] **1. `baas-log.md`** — New session entry added at the top of Change History. Must include: session number, date, one-line summary, New/Updated Files table, Key Decisions, Build Verification, and Confirmed Platform Versions block.
  - Run `git log --oneline -1 -- baas-engine/` to get the SHA
  - Read versions from `baas-engine/pom.xml`

- [ ] **2. `CLAUDE.md`** — Updated:
  - Confirmed Platform Versions table at top
  - Module Catalogue (new modules marked ✅)
  - Any new gotchas discovered

- [ ] **3. API docs** — If ANY controller file in `baas-engine/` was touched this session:
  - Check for new or changed `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` annotations
  - Update API reference (when created in a later session)
  - Only sessions that touched ZERO controller files may skip this step

- [ ] **4. Deployment-agnostic check** — If a new service was added this session:
  - [ ] `Dockerfile` committed
  - [ ] CI workflow committed (`.github/workflows/`)
  - [ ] `infrastructure/docker-compose.yml` entry added

- [ ] **5. Commit and push** — `git add CLAUDE.md baas-log.md && git commit && git push origin main`
  - The pre-push hook will block if `Confirmed Platform Versions` is missing from either file

### Rationalisation Traps

| Thought | Why it's wrong |
|---------|---------------|
| "The frontend didn't touch the backend" | `CLAUDE.md` and `baas-log.md` still need updating |
| "I'll do docs next session" | The next session starts cold. Missing docs will be missed again. |
| "It was just a small fix" | Every session gets a log entry, no exceptions |
| "Vercel handles the deploy, Dockerfile is redundant" | Vercel is one target. Dockerfile is the portability contract. Both must exist. |

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
| 1A | `baas-engine` — multi-tenancy + core APIs | 🔄 Tasks 1–9 done, 10–16 pending |
| 1B | `baas-ncube` — CBN format + BVN/NIN | ⬜ Not started |
| 1C | `baas-backoffice` — React shell | ⬜ Not started |
| 1D | `baas-portal` — React developer portal | ⬜ Not started |
| 1E | Infrastructure — Docker Compose + CI/CD | ⬜ Not started |

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
