# Accounts Domain Track — Design

**Date:** 2026-06-14
**Status:** Approved (brainstorm) → ready for planning
**Track:** second per-domain track on `baas-backoffice` (after Customers). A vertical slice across `baas-engine` (backend) + `baas-backoffice` (frontend), delivered as **two independently-mergeable PRs** (one service per PR).

---

## 1. Goal

Surface, harden, and complete the **Accounts** domain end-to-end: a bank-staff console to open accounts against customers, move money (deposit/withdraw), run the account lifecycle (freeze / unfreeze / close) with an audit trail, and read the transaction ledger — built on the existing `Account`/`Transaction`/`AccountService` spine already present in `baas-engine`.

This track mirrors the just-shipped Customers track in shape and conventions (list + detail + create-modal + lifecycle state machine + append-only event history + RBAC + editable Figma frames).

## 2. Starting point (what already exists)

**Backend (`baas-engine`, `com.nubbank.baas.engine.account`):**
- `Account` entity — tenant-schema (no `@Table(schema=…)`); fields: `id`, `customer_id` (`@ManyToOne` Customer, NOT nullable), `accountNumber` (unique), `accountTypeLabel` (String — a label, not an enum), `status` (`AccountStatus`), `balance`, `availableBalance`, `currencyCode` (default `NGN`), `minimumBalance`, `allowOverdraft`, `overdraftLimit`, `version`, `createdAt`, `updatedAt`.
- `AccountStatus` enum: `ACTIVE`, `FROZEN`, `CLOSED`.
- `AccountService`: `open`, `getById`, `deposit` (writes `Transaction` CREDIT), `withdraw` (balance-floor check + `Transaction` DEBIT), `getTransactions` (paginated), plus internal card-auth debit/credit.
- `Transaction` entity: append-only (`CREDIT`/`DEBIT`, `running_balance`, `reference`, `description`).
- `AccountController` (`/baas/v1/accounts`): `POST /` (open), `GET /{id}`, `POST /{id}/deposit`, `POST /{id}/withdraw`, `GET /{id}/transactions`. **No `@PreAuthorize` guards. No list endpoint. No lifecycle commands.**
- RBAC seeded (`V2`): `READ_ACCOUNT`, `CREATE_ACCOUNT`, `DEPOSIT`, `WITHDRAW`. (No `UPDATE_ACCOUNT` yet.)
- Latest tenant migration: `V5__customer_kyc_events.sql`. **Next free: `V6`.**

**Frontend (`baas-backoffice`):**
- Nav items `/accounts`, `/deposits`, `/teller`, `/charges` exist in `layout/nav-config.ts` but **route to nothing (404)**.
- No `src/features/accounts/` directory.
- Customers track is the template: `useApiClient` + `unwrapResult`/`extractPage` + `qk` query keys, `PERMISSIONS` + `RequireRoutePermission`, `CommandModal`/`FormField`/`Input`/`DataTable`/`StatusBadge`, `src/lib/format.ts` (`humanizeStatus`/`formatDateTime`), hand-seeded `src/api/schema.d.ts`.

## 3. Domain rules (the decisions this spec locks in)

### 3.1 Lifecycle state machine
Each transition records a **reason** (required, non-blank) and appends an immutable row to a new `account_status_events` table — identical in shape to `customer_kyc_events`.

| From | Command | To | Guard |
|------|---------|----|-------|
| `ACTIVE` | `FREEZE` | `FROZEN` | — |
| `FROZEN` | `UNFREEZE` | `ACTIVE` | — |
| `ACTIVE` | `CLOSE` | `CLOSED` | **balance must be 0** |

`CLOSED` is terminal. **Close is only reachable from `ACTIVE`** — a frozen account must be unfrozen first (legal-hold realism). Any other (from, command) pair → `400 BAD_REQUEST` (`INVALID_ACCOUNT_TRANSITION`). Close with non-zero balance → `409 CONFLICT` (`ACCOUNT_BALANCE_NONZERO`).

### 3.2 Money-movement gating (legal-hold model)
Added to `AccountService` ahead of the existing balance logic:

| Operation | Allowed when status… | Else |
|-----------|----------------------|------|
| **Deposit** (credit) | `ACTIVE` **or** `FROZEN` | `409 ACCOUNT_NOT_ACCEPTING_CREDITS` (only `CLOSED` blocks credits) |
| **Withdraw** (debit) | `ACTIVE` only | `409 ACCOUNT_NOT_ACCEPTING_DEBITS` (`FROZEN` and `CLOSED` block debits) |

Withdraw keeps the existing minimum-balance / overdraft floor check **after** the status gate.
Net: a freeze blocks debits but **credits still post**; closing needs a zero balance.

### 3.3 RBAC
| Endpoint group | Permission |
|----------------|-----------|
| All reads (list, detail, transactions, status-events) | `READ_ACCOUNT` |
| Open account | `CREATE_ACCOUNT` |
| Deposit | `DEPOSIT` |
| Withdraw | `WITHDRAW` |
| Freeze / Unfreeze / Close | **`UPDATE_ACCOUNT`** (new — one code for all three lifecycle commands, mirroring the single `UPDATE_CUSTOMER`) |

`UPDATE_ACCOUNT` is seeded in `V6` (permission row + role→permission mapping) and added to the first-party full-tenant-authority set, and to `baas-backoffice` `src/lib/rbac.ts` `PERMISSIONS`.

### 3.4 Search & deferral
- List `search` matches **`account_number` only** (plaintext `ILIKE`). Status filter is exact.
- **Customer-name search in the accounts list is deferred** (customer names are encrypted; it would need the customer blind-index join) → `DEF-1C-32`.

## 4. Backend design (`baas-engine` — PR A)

**Package:** `com.nubbank.baas.engine.account` (extend existing).

- **`AccountCommand`** enum: `FREEZE`, `UNFREEZE`, `CLOSE`.
- **`AccountStatusEvent`** entity — `id`, `accountId` (FK → `accounts.id`), `fromStatus`/`toStatus` (String, decoupled from the enum for audit stability, same choice as `CustomerKycEvent`), `reason`, `changedBy`, `changedAt` (`@PrePersist`); no `@Version`; append-only.
- **`AccountStatusEventRepository`** — `findByAccountIdOrderByChangedAtAsc(UUID)`.
- **`V6__account_status_events.sql`** — creates `account_status_events` (FK `account_id` → `accounts(id)`), seeds `UPDATE_ACCOUNT` permission + role mapping.
- **`AccountService`**:
  - `transition(UUID id, AccountCommand cmd, String reason)` — `@Transactional`; loads account, computes `target(from,cmd)`, validates guard (close ⇒ `ACTIVE` + zero balance), sets status, writes `AccountStatusEvent`, saves. `currentPrincipal()` for `changedBy` (null/anonymous-safe, same helper pattern as Customers).
  - `statusEvents(UUID id)` — 404 if account absent.
  - `list(int page, int size, AccountStatus status, String search)` — JPQL paginated query `JOIN FETCH a.customer` with an **explicit `countQuery`** (Spring Data cannot derive a count over a `JOIN FETCH` — known gotcha); `status` nullable (skip filter when null), `search` `ILIKE` on `account_number` (skip when blank); returns `Page<Account>` mapped to `AccountSummaryResponse`.
  - `open(OpenAccountRequest req)` — **extended** to accept an optional `openingDeposit` (default `0`, must be `≥ 0`). When `> 0`, the account is created with that starting `balance`/`availableBalance` and an initial immutable `Transaction` (`CREDIT`, reference `OPENING_DEPOSIT`) is written **atomically in the same transaction** as the account insert. When `0`, behaviour is unchanged (zero-balance account, no transaction row).
  - `deposit`/`withdraw` — prepend the §3.2 status gate.
- **DTOs:**
  - `AccountSummaryResponse(id, accountNumber, customerId, customerName, accountTypeLabel, status, balance, currencyCode)` — `customerName` resolved from the `JOIN FETCH`-loaded `Account.customer` (first+last, decrypted via the converter on the Customer entity at load time — no separate repository round-trip, so the cross-repository converter pitfall does not apply).
  - `AccountDetailResponse(id, accountNumber, customerId, customerName, accountTypeLabel, status, balance, availableBalance, currencyCode, minimumBalance, allowOverdraft, overdraftLimit, openedAt)`.
  - `AccountStatusEventResponse(id, fromStatus, toStatus, reason, changedBy, changedAt)`.
  - `AccountTransitionRequest(reason)` — `@NotBlank`.
  - (existing) open & transaction request DTOs reused; open response returns the new detail shape.
- **`AccountController`** — add `@PreAuthorize` to all (§3.3); add:
  - `GET /baas/v1/accounts` (list, query params `page,size,status,search`) → `Page<AccountSummaryResponse>`.
  - `GET /baas/v1/accounts/{id}` → `AccountDetailResponse` (upgrade existing).
  - `GET /baas/v1/accounts/{id}/status-events` → `List<AccountStatusEventResponse>`.
  - `POST /baas/v1/accounts/{id}/freeze|unfreeze|close` (body `AccountTransitionRequest`).
- **Envelope/errors:** `ApiResponse<T>`; `BaasException.badRequest/conflict/notFound` with the codes in §3.1–3.2.

**TDD:** each task is test-first (state-machine combos incl. invalid transitions; close-nonzero-balance 409; deposit-on-frozen passes; withdraw-on-frozen 409; list filter+search; RBAC 403 on missing authority; status-events 404).

## 5. Frontend design (`baas-backoffice` — PR B)

**Directory:** `src/features/accounts/` (mirror `customers/`).

- **`use-accounts.ts`** — `useAccounts(params)` (list; clean query object stripping `undefined`; `qk.list('accounts', query)`), `useAccount(id)`, `useAccountStatusEvents(id)`, `useAccountTransactions(id)`, `useOpenAccount`, `useDeposit(id)`, `useWithdraw(id)`, `useAccountTransition(id)` (freeze/unfreeze/close; invalidates detail + list + status-events). Types: `AccountStatus`, `AccountRow`, `AccountDetail`, `AccountTransaction`, `StatusEvent`, `AccountListParams`, `OpenAccountBody`, `MoneyBody`, `AccountCommand`.
- **`account-form.ts`** — Zod for open-account (customerId required; accountTypeLabel required; currencyCode default `NGN`; openingDeposit optional ≥ 0).
- **`open-account-modal.tsx`** — `CommandModal` (conditionally mounted for a fresh form). **Customer picker** = debounced search box hitting the Customers list endpoint (`GET /customers?search=`), select a result → `customerId`. Then type select (Savings/Checking/Current), currency, optional opening deposit.
- **`accounts-list.tsx`** + **`account-status-badge.tsx`** — `DataTable` (Account #, Customer, Type, Status badge, Balance); status filter dropdown; account-number search (debounced); Open-account button (`CREATE_ACCOUNT`-gated).
- **`money-modal.tsx`** — deposit/withdraw (amount > 0, optional reference); title/verb by mode.
- **`account-action-modal.tsx`** — freeze/unfreeze/close (required reason), conditionally mounted.
- **`account-status-history.tsx`** — append-only timeline (from→to, reason, changedBy, humanised date).
- **`transaction-ledger.tsx`** — paginated CREDIT/DEBIT rows (amount, running balance, reference, date).
- **`account-detail.tsx`** — header (account #, status badge, customer link, balance), details card, **action buttons per state + permission** (`ACTIONS` map gated by `UPDATE_ACCOUNT`/`DEPOSIT`/`WITHDRAW`; `?? []` runtime guard), ledger, status history.
- **Routing:** `account-routes` (`/accounts`, `/accounts/:id`) `READ_ACCOUNT`-guarded via `RequireRoutePermission`, spread into `router.tsx`. Add `UPDATE_ACCOUNT` to `rbac.ts` `PERMISSIONS`.
- **`src/api/schema.d.ts`** — hand-seed the new account paths/params until `gen:api`.
- **Display:** all statuses/dates through `src/lib/format.ts`.
- **e2e** `e2e/accounts.spec.ts` — open → deposit → freeze → withdraw-blocked → unfreeze → close (engine stubbed, dev-auth).
- **Docs:** update `docs/backoffice-operations.md` (routes, RBAC codes, endpoints consumed).

## 6. Action map (frontend ↔ backend state machine)

```
ACTIVE  → [freeze] [close*] [deposit] [withdraw]      (*close enabled only when balance == 0)
FROZEN  → [unfreeze] [deposit]
CLOSED  → (no actions)
```
Buttons render only when the operator holds the permission AND the status allows the action. `?? []` guards against an out-of-union wire status.

## 7. Figma (SESSION COMPLETION GATE item 7)

Add an **"Accounts — As Built"** section to the [NubBank BaaS — Backoffice](https://www.figma.com/design/gEDnLrLD4UrChcND0yCdZ9/NubBank-BaaS-%E2%80%94-Backoffice) file (fileKey `gEDnLrLD4UrChcND0yCdZ9`) as natively-editable frames reusing the **NubBank Tokens** collection + the existing shell: **Accounts list · Account detail · Open-account modal · Deposit/Withdraw modal · Freeze/Close action modal**. Record the frames in `baas-log.md`.

## 8. Delivery

- **PR A** — `baas-engine` Accounts lifecycle (branch `feat/baas-engine-accounts-lifecycle`).
- **PR B** — `baas-backoffice` Accounts console (branch `feat/baas-backoffice-accounts`).
- Each executed **subagent-driven** with a **full two-stage review per task** (spec compliance → code quality), then a final comprehensive review per PR.
- Zero file overlap between PRs; mergeable in any order; the frontend degrades gracefully if PR A is undeployed.

## 9. Deferred items

| ID | Item | Why deferred |
|----|------|--------------|
| `DEF-1C-32` | Customer-name search in the accounts list | Customer names are encrypted; needs the customer blind-index join (mirror `NameTokenizer`). Account-number search ships now. |

## 10. Out of scope (YAGNI)

- `/deposits`, `/teller`, `/charges` nav items (separate future tracks).
- Account products / interest accrual / statements export.
- Min-balance / overdraft configuration in the open-account modal (entity defaults stand).
- Cross-link "Open account" from the Customer detail page (later add-on; reuses `useOpenAccount`).
