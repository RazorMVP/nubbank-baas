# Maker-Checker / Four-Eyes — Design Spec

**Date:** 2026-06-18
**Status:** Approved (design) — ready for implementation plan
**Closes:** DEF-1C-13 (maker-checker beyond engine support)
**Depends on:** Spec A — Granular Partner RBAC (`2026-06-18-partner-rbac-design.md`) — supplies the maker/approver roles and the `APPROVE_*` permission space this spec populates.

---

## 1. Problem & context

A sensitive command — starting with **account-open** — currently executes synchronously and is attributed to a single actor. There is no dual-control: the same person who initiates a command also completes it. `AccountStatus` has no pending state, and there is no maker-checker machinery anywhere in the engine (DEF-1C-13).

This spec adds a **general, command-first maker-checker framework**: a guarded command, instead of executing, is captured as a `PENDING` task that a *different* person must approve before it executes. The framework is generic by construction; `ACCOUNT_OPEN` is the first registered command, and others (large withdrawals, role grants, key issuance, account close) can be placed behind it later by registering a handler.

The central difficulty maker-checker introduces is **time**: a deferred command spans a gap between *authoring* (submit) and *executing* (approve), during which the world can drift. The whole design treats the **approve moment as the source of truth** and everything before it as provisional.

---

## 2. Goals / Non-goals

**Goals**
- A generic, reusable maker-checker engine; `ACCOUNT_OPEN` guarded first.
- Per-partner, opt-in, production-only enforcement with a viability guard.
- Four-eyes correctness across the time gap: re-validation, authority re-checks, execute-exactly-once.
- Pending work fully visible via an approvals inbox.

**Non-goals**
- The RBAC model itself (Spec A).
- Guarding commands beyond `ACCOUNT_OPEN` in v1 (the *framework* supports them; the *catalogue* grows later).
- Task expiry/TTL in v1 (schema seam reserved — §10).
- Compensating actions for already-executed commands (a withdrawn/rejected task never executed; reversing an executed one is out of scope).

---

## 3. Decisions (from brainstorming)

| # | Decision |
|---|----------|
| D1 | **General framework**, `ACCOUNT_OPEN` first; grow the guarded-command catalogue over time. |
| D2 | **Command-first**: a `maker_checker_tasks` table stores the serialized command; on approval it is **replayed** through the real service. Un-approved intent never enters the domain tables. |
| D3 | **Per-partner, per-command-type config, default OFF** (opt-in). Back-compat is automatic. |
| D4 | **Enforced only in `PRODUCTION`**; `SANDBOX` ignores maker-checker (solo testing). |
| D5 | **Viability guard**: can't enable a command unless the org has ≥1 distinct eligible approver. |
| D6 | **Execute is authoritative**: submit-time validation is courtesy/UX; the approve path re-validates against current state via the *same* service method the synchronous path uses. |
| D7 | **Per-command `APPROVE_*` permissions** (granular); `PARTNER_APPROVER` bundles all current `APPROVE_*`. |
| D8 | **Maker may withdraw** their own `PENDING` task. **No TTL in v1**, but the schema seam is reserved. |

---

## 4. Data model (tenant-schema)

### 4.1 `maker_checker_tasks`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `command_type` | `VARCHAR` | e.g. `ACCOUNT_OPEN` |
| `payload` | `JSONB` | the serialized request DTO |
| `made_by` | UUID | partner_user UUID (soft ref to `public.partner_users`) |
| `made_at` | `TIMESTAMPTZ` | |
| `status` | `VARCHAR` | `PENDING \| APPROVED \| REJECTED \| WITHDRAWN` (enum kept **open** to a future `EXPIRED` — §10) |
| `checked_by` | UUID null | set on approve/reject |
| `checked_at` | `TIMESTAMPTZ` null | |
| `reject_reason` | `TEXT` null | |
| `result_id` | UUID null | the resource created on approval (e.g. the account id) |
| `expires_at` | `TIMESTAMPTZ` null | **reserved, unused in v1** (TTL seam) |
| `version` | `BIGINT` | optimistic lock (idempotent approve) |
| `created_at`/`updated_at` | `TIMESTAMPTZ` | |

### 4.2 `maker_checker_config`
| Column | Type | Notes |
|--------|------|-------|
| `command_type` | `VARCHAR` PK | one row per guardable command |
| `enabled` | `BOOLEAN NOT NULL DEFAULT false` | absent/false ⇒ not guarded |
| `version`, `created_at`, `updated_at` | | |

### 4.3 New seeded permissions (tenant `permissions`)
- `APPROVE_ACCOUNT` — required to approve an `ACCOUNT_OPEN` task (one `APPROVE_*` per guarded command; more added with each new command).
- `MANAGE_MAKER_CHECKER` — gates the config endpoints.

`PARTNER_APPROVER` (Spec A) bundles all current `APPROVE_*`; `PARTNER_ADMIN` (dynamic full) can always approve and manage config.

---

## 5. The flow

A guarded command's entry point delegates to the framework:

```
isGuarded(commandType) == (config.enabled AND environment == PRODUCTION)
```

- **Not guarded** → execute normally → `201` + resource (today's behaviour, unchanged).
- **Guarded** → run the handler's **submit-time `validate(payload)`** (bean/DTO + cheap existence checks — *courtesy only, non-authoritative*), persist a `PENDING` task, return **`202` + `{ taskId, status: PENDING }`**. Nothing enters the domain tables.

**Approve** (`POST /maker-checker/tasks/{id}/approve`), all inside **one transaction**:
1. Load the task `FOR UPDATE`; require `status = PENDING` (else `409`).
2. **Four-eyes + authority re-checks (§7.1):** `checked_by ≠ made_by`; checker holds the command's `APPROVE_*`; **maker is still active and still holds the submit authority** (closes the revocation backdoor).
3. **Re-validate + execute via the same service method** the synchronous path uses (`AccountService.open(...)`) — re-validation against *current* state is therefore automatic and identical to a synchronous command.
4. Flip `PENDING → APPROVED` with a status precondition (`WHERE status='PENDING'`) + `@Version`; record `checked_by`, `checked_at`, `result_id`.

If step 2 or 3 fails, the whole transaction **rolls back** — the side effect and the flip undo together, the task stays `PENDING`, and the checker receives the precise failing reason (`409`/`422`). The checker then rejects, or the maker withdraws and resubmits.

**Reject** (`/reject`, reason) → `REJECTED`, nothing created. **Withdraw** (`/withdraw`) → the *maker* cancels their own `PENDING` task (§7.3).

---

## 6. Command registry (what makes it general)

Each guarded command registers a Spring bean implementing a handler interface:

| Method | Purpose |
|--------|---------|
| `commandType()` | e.g. `ACCOUNT_OPEN` |
| `requiredAuthorityToSubmit()` | e.g. `CREATE_ACCOUNT` — the maker must hold it |
| `requiredAuthorityToApprove()` | e.g. `APPROVE_ACCOUNT` — the checker must hold it (binding declared centrally, not scattered) |
| `validate(payload)` | submit-time courtesy check (subset of real validation) |
| `execute(payload) → resultId` | **delegates to the real, fully-validating service method** — never a stripped re-implementation |

`MakerCheckerService` looks the handler up by `command_type` at submit, approve, and on the dry-run validity check. Adding a new guarded command = implement a handler + seed its `APPROVE_*` permission + (optionally) a default config row. The `ACCOUNT_OPEN` handler's `execute` calls `AccountService.open(...)`.

**Cardinal rule:** the deferred path must invoke the *identical* service entry point as the synchronous path. It may never bypass a rule the synchronous path enforces.

---

## 7. Authority, four-eyes & the time gap

### 7.1 Authority model
- **Maker** holds the normal command authority to submit (`CREATE_ACCOUNT`) — `PARTNER_MAKER` has it.
- **Checker** holds the command's `APPROVE_*` (`APPROVE_ACCOUNT`) — `PARTNER_APPROVER` has it.
- `checked_by ≠ made_by` enforced **per task** — the same user may be maker on one task and checker on another; never approver of their *own*.
- **Re-checked at approve, on both sides:** checker authority is current, and the **maker is still active and still authorised** — a command authored by a since-revoked maker cannot execute.
- The executed resource is attributed to the **maker** (`made_by`); the task records both maker and checker — a built-in audit trail.

### 7.2 Informed approval (live validity)
`GET /maker-checker/tasks/{id}` runs the handler's validation as a **dry-run against current state** and returns a `valid` / `wouldFailBecause` indicator, so the checker decides against today's reality, not the snapshot.

### 7.3 Withdraw
Only the original `made_by`, only while `PENDING`, terminal (`WITHDRAWN`), audited. The withdraw-vs-approve race is resolved by the same status-precondition + optimistic lock as double-approve — first commit wins, the loser gets `409`.

---

## 8. Config & enforcement

- Enforced only when `maker_checker_config.enabled` **and** `PartnerContext.environment == PRODUCTION`.
- **Viability guard:** `PUT /maker-checker/config` enabling a command requires the org to have ≥1 distinct user holding that command's `APPROVE_*` (else `409 NO_ELIGIBLE_APPROVER`) — a partner can't lock itself out of a command.
- Config managed by `MANAGE_MAKER_CHECKER` (in `PARTNER_ADMIN`'s dynamic-full set).

---

## 9. API surface

| Method | Path | Auth |
|--------|------|------|
| `GET` | `/baas/v1/maker-checker/tasks?status=&type=` | approvals inbox (read; org-scoped) |
| `GET` | `/baas/v1/maker-checker/tasks/{id}` | read; includes live-validity (§7.2) |
| `POST` | `/baas/v1/maker-checker/tasks/{id}/approve` | command `APPROVE_*` + `checker≠maker` |
| `POST` | `/baas/v1/maker-checker/tasks/{id}/reject` | command `APPROVE_*` |
| `POST` | `/baas/v1/maker-checker/tasks/{id}/withdraw` | original maker only |
| `GET`/`PUT` | `/baas/v1/maker-checker/config` | `MANAGE_MAKER_CHECKER` |
| `POST /baas/v1/accounts` | (existing) | returns **`202`+taskId** when guarded, else `201` |

Other-org task IDs → `404` (enumeration-safe).

---

## 10. Guardrails / edge cases & deferrals

| Case | Behaviour |
|------|-----------|
| **Double-approve / approve-vs-withdraw race** | Status precondition + `@Version`: first commit wins, loser → `409`. Execute-exactly-once is structural. |
| **Stale command** (dependency drifted) | Re-validation at execute fails → transaction rolls back → task stays `PENDING` → checker rejects / maker resubmits. Never executes wrong. |
| **Revoked maker** | Approve blocked (maker no longer active/authorised). |
| **`202` contract** | Clients of a guarded `POST /accounts` must handle the deferred response shape; documented in `backoffice-operations.md` + API reference. |
| **Self-approval via dual roles** | Holding both maker and approver roles is fine; `checker≠maker` still blocks approving one's own task. |
| **Viability** | Can't enable a command with no eligible approver (`409`). |
| **TTL (deferred)** | No expiry in v1. Seam reserved (`expires_at` column + open status enum); later add = default `expires_at` at submit + `@Scheduled` sweeper (`PENDING` past expiry → `EXPIRED` + notify) + remaining-TTL in the inbox — **no migration of in-flight tasks**. |

---

## 11. Testing strategy

- **Guarded happy path:** `PARTNER_MAKER` submits account-open under guarded+PRODUCTION config → `202`, **no account row exists**; `PARTNER_APPROVER` approves → account created, attributed to the maker, `result_id` set.
- **Four-eyes:** maker approving own task → `403`/`409`.
- **Authority:** `PARTNER_VIEWER` can't submit; `PARTNER_MAKER` can't approve (no `APPROVE_ACCOUNT`).
- **Drift:** customer deleted between submit and approve → approve fails (`409`/`422`), task remains `PENDING`, no account created.
- **Revoked maker:** strip the maker's role after submit → approve blocked.
- **Config / environment:** config off, or `SANDBOX` → normal `201`, no task.
- **Viability guard:** enable with no eligible approver → `409`.
- **Idempotency:** concurrent double-approve → one `200`, one `409`, exactly one account.
- **Withdraw:** maker withdraws own `PENDING` → `WITHDRAWN`; another user withdraws → `403`; withdraw after approve → `409`.
- **Live validity:** task-detail dry-run flags a now-invalid task before the checker acts.

---

## 12. Relationship to Spec A

- The `APPROVE_ACCOUNT` permission and `MANAGE_MAKER_CHECKER` live in the same tenant `permissions` catalogue Spec A introduced; `PARTNER_APPROVER` (Spec A) is the role that carries `APPROVE_*`.
- Four-eyes is only *possible* because Spec A lets a partner have ≥2 users with distinct roles — implement Spec A first.
