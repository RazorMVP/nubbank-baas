# Phase 1C — Parallel Execution Playbook (git worktrees)

| | |
|---|---|
| **Status** | Draft — companion to the Phase 1C design spec |
| **Date** | 2026-05-29 |
| **Execution model** | Pattern B — git worktrees + separate Claude Code sessions, PR-based supervision |
| **Spec** | `docs/superpowers/specs/2026-05-29-nubbank-baas-phase1c-backoffice-design.md` |
| **Supervisor** | You (review every PR; you are the integration authority) |

---

## 0. Mental model

One repo, several **worktrees**. A worktree is a second (third, fourth…) working directory backed by the
**same** `.git` object store but checked out to a **different branch**. Each worktree gets its **own Claude
Code session**. The sessions never share a working directory, so they can edit files at the same time without
stepping on each other. They integrate **only through PRs into `main`**, which you review.

```
~/nubbank-baas              (main)               ← integration; you drive merges here
~/nb-card                   (feature/phase1c-card)        ← Claude session A
~/nb-fep                    (feature/phase1c-fep)         ← Claude session B
~/nb-backoffice             (feature/phase1c-backoffice)  ← Claude session C
~/nb-platform-admin         (feature/phase1c-platform-admin) ← Claude session D
~/nb-custodian              (feature/phase1c-custodian)   ← Claude session E
~/nb-foundation             (feature/phase1c-foundation)  ← Claude session F (Stage 1)
```

The golden rule of parallelism here: **a track is only parallel-safe if it does not need another track's
uncommitted code.** That's why Stage 1 (Foundation) lands and merges to `main` *before* the parallel tracks
start — they all branch from a `main` that already has the operator-identity + RBAC interfaces.

---

## 1. Branch naming

| Branch | Track | Stage |
|---|---|---|
| `feature/phase1c-foundation` | Operator identity (D1), RBAC wiring (D2), deferred registry (D10) | 1 |
| `feature/phase1c-card` | `baas-card` service (D6) | 2 |
| `feature/phase1c-fep` | `baas-fep` service + BIN routing (D7) | 2 |
| `feature/phase1c-custodian` | Read-only DS trio (D3), admin audit (D4), export (D5) | 3 |
| `feature/phase1c-backoffice` | `baas-backoffice` React app (D8) | 4 |
| `feature/phase1c-platform-admin` | `baas-platform-admin` React app (D9) | 4 |

Convention: always `feature/phase1c-<track>`. Sub-work inside a track stays on that branch (or short-lived
`feature/phase1c-<track>--<subtask>` branches that merge back into the track branch before the track PR).

---

## 2. Worktree lifecycle

### Create (when a track starts)

```bash
cd ~/nubbank-baas
git fetch origin
# branch from the LATEST main so the track inherits merged Foundation interfaces
git worktree add -b feature/phase1c-card ~/nb-card origin/main
```

The `superpowers:using-git-worktrees` skill is the canonical procedure — invoke it in the session that
creates the worktree. It handles the dedicated-directory + clean-base details. This playbook is the
Phase-1C-specific layer on top of it.

### Work (inside the worktree)

```bash
cd ~/nb-card
claude            # start a fresh Claude Code session, scoped to this directory
```

In that session, point Claude at the spec + the executing-plans flow:
- It reads `docs/superpowers/specs/2026-05-29-nubbank-baas-phase1c-backoffice-design.md`.
- It executes the relevant track of the **implementation plan** (generated next, by `writing-plans`).
- It runs the BaaS Session Completion Gate locally (tests, `baas-log.md`, `CLAUDE.md`) **for its track**.

### Finish (track complete)

```bash
cd ~/nb-card
git push -u origin feature/phase1c-card
gh pr create --base main --head feature/phase1c-card --title "Phase 1C: baas-card service" --fill
```

You review the PR. On merge:

```bash
cd ~/nubbank-baas
git worktree remove ~/nb-card        # cleans the worktree; branch already merged
git worktree prune
```

---

## 3. Stage sequencing (the dependency spine)

```
Stage 1  ── Foundation (1 worktree, sequential) ───────────────┐
            D1 operator identity, D2 RBAC, D10 registry          │ merge to main
            ▼                                                     │ BEFORE Stage 2 starts
Stage 2  ── Card  ∥  FEP   (2 worktrees, parallel) ─────────────┤
            D6        D7 (consumes Card's BIN-lookup interface)   │
            ▼                                                     │
Stage 3  ── Custodian (1 worktree; may overlap late Stage 2) ───┤
            D3 read-only DS, D4 admin audit, D5 export           │
            ▼                                                     │
Stage 4  ── Backoffice  ∥  Platform-Admin (2 worktrees, parallel)┤
            D8            D9                                       │
            ▼                                                     │
Stage 5  ── Integration & hardening (main, sequential) ─────────┘
```

**Hard ordering constraints (do not violate):**
1. **Foundation merges to `main` before any Stage 2/3/4 worktree is created.** Parallel tracks branch from a
   `main` that already carries D1+D2 interfaces, so they don't re-implement or guess auth shapes.
2. **FEP depends on Card's BIN-lookup interface.** Either (a) land Card's BIN-range read endpoint first and
   have FEP branch after, or (b) define the BIN-lookup contract as a stub interface in Foundation so both can
   proceed against the contract. Prefer (b) if you want true Card∥FEP parallelism from Stage 2 start.
3. **Custodian (D3 read-only DS) and the admin namespace must exist before Platform-Admin (D9)** has a real
   API to call. Platform-Admin can start against a mocked `/baas-admin/v1/**` (OpenAPI codegen from the
   committed admin OpenAPI doc) and switch to the real service when Custodian merges.
4. **Backoffice (D8) only needs Foundation** (auth + RBAC) + the existing engine endpoints — it can start as
   soon as Foundation merges, in parallel with everything else.

**Peak concurrency: 2–3 active worktrees.** Don't open all six at once; it just creates merge debt.

---

## 4. PR-review cadence (how you supervise)

You are the integration authority. The rhythm:

- **One PR per track**, into `main`. Not one giant PR — but also not a PR per file. Track-sized.
- **Daily-ish check-in** per active worktree: skim the branch's commits, run/observe its tests, steer if a
  track is drifting from the spec.
- **Merge order matches the dependency spine** (§3). Never merge a downstream track before its upstream
  dependency is on `main`.
- **Rebase, don't merge-commit, the track branches** onto the latest `main` before opening the PR, so the PR
  diff is clean:
  ```bash
  cd ~/nb-card && git fetch origin && git rebase origin/main
  ```
- **Conflict policy:** because tracks own disjoint directories (`baas-card/`, `baas-fep/`,
  `baas-backoffice/`, …), the only real conflict surface is shared files: `CLAUDE.md`, `baas-log.md`,
  `infrastructure/docker-compose.yml`, root configs. Mitigation:
  - Each track appends its `baas-log.md` session entry **at the top**; resolve conflicts by keeping both
    entries in chronological order at merge time.
  - `CLAUDE.md` module-catalogue edits: keep additive; resolve by union.
  - `docker-compose.yml`: each new service is a separate block; union-merge.
- **The BaaS Session Completion Gate runs per merge to `main`**, not per worktree push. A track's local gate
  run is a pre-check; the authoritative gate is when its PR lands on `main` and you cut the session entry.

---

## 5. Per-track kickoff checklist

When you start a track's worktree + session, the session should:

- [ ] `git worktree add -b feature/phase1c-<track> ~/nb-<track> origin/main` (from latest main)
- [ ] Confirm it branched from a `main` that has the dependencies it needs (§3)
- [ ] Read the spec + its assigned track of the implementation plan
- [ ] Invoke `superpowers:executing-plans` (review checkpoints) or `subagent-driven-development` as the plan
      dictates
- [ ] Honor the multi-tenancy invariants (spec §10) and the multi-tenancy test floor (spec §11) for its track
- [ ] Keep changes inside its directory; touch shared files (`CLAUDE.md`, `baas-log.md`, compose) only
      additively
- [ ] Before PR: rebase onto `origin/main`, run the local gate (tests green), write its `baas-log.md` entry
- [ ] Open the track PR; hand back to you for review

---

## 6. What stays sequential on `main`

Stage 5 (integration & hardening) happens on `main` directly, not in a worktree:

- Wire the real (un-mocked) inter-service calls now that all services are merged.
- End-to-end Playwright flows that span backoffice → engine → card → fep.
- The Phase-1C **phase-gate review** (per `/baas` skill) against the whole phase as a unit, captured in
  `docs/phase-gate-reviews.md`.
- Final `CLAUDE.md` / `baas-log.md` reconciliation, API docs, CBN gap-analysis pass.

---

## 7. When to reach for the other patterns (not Pattern B)

- **Pattern A — in-session subagents:** use *inside* any track session for short, independent burst work
  (e.g. "generate the 29-role seed + the Zod schemas + the OpenAPI client in parallel"). Minutes-to-30-min
  horizon. This composes with Pattern B; it is not a replacement.
- **Pattern C — Claude Agent SDK (hosted):** deferred. Revisit only if a specific long-running, unattended
  task appears (e.g. a multi-hour migration sweep). Not needed for the supervised Phase 1C build.

---

## 8. Quick command reference

```bash
# list worktrees
git worktree list

# add a track
git worktree add -b feature/phase1c-<track> ~/nb-<track> origin/main

# remove a finished track
git worktree remove ~/nb-<track> && git worktree prune

# rebase a track before PR
cd ~/nb-<track> && git fetch origin && git rebase origin/main

# open a track PR
gh pr create --base main --head feature/phase1c-<track> --fill
```

> Bypass the pre-push hook only with `git push --no-verify` **and** a one-line reason in `baas-log.md` under
> the current session entry (per the `/baas` Session Completion Gate).
