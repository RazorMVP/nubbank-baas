# Customers Track — Design

**Date:** 2026-06-10
**Status:** Approved (brainstorm) → ready for implementation plan
**Track:** First per-domain track on the tenant backoffice (`baas-backoffice`), with the
supporting endpoints on `baas-engine`.

---

## Goal

Make the **Customers** domain fully usable in the tenant backoffice: an operator can find a
customer, view their full profile, create and edit customers, and drive the KYC lifecycle
(activate / suspend / reactivate / close) with a recorded reason and an auditable history — all
scoped to the partner's own tenant schema.

This is the first per-domain track and therefore sets the **template** every later track
(Accounts, Loans, Payments, …) will copy: a `baas-engine` REST surface + a `baas-backoffice`
"list + detail + modals" feature, shipped as two zero-overlap PRs.

## Architecture (vertical slice)

```
baas-backoffice (React 19)                     baas-engine (Spring Boot 3.5)
  src/features/customers/                         com.nubbank.baas.engine.customer
   ├─ customers-list   ──GET /customers?…──────►   CustomerController.list (filter+search)
   ├─ customer-detail  ──GET /customers/{id}────►   CustomerController.getById (widened)
   │                   ──GET …/kyc-events───────►   CustomerController.kycEvents
   ├─ create/edit modal─POST/PUT /customers─────►   CustomerController.create / update
   └─ kyc action modal ─POST …/{activate|…}─────►   CustomerController.<transition>
                                                    CustomerService (state machine + tokens)
                                                    customers (+ name_search_tokens)
                                                    customer_kyc_events (new table)
```

Multi-tenancy, auth, and the response envelope are unchanged — every query routes to the
partner schema via the existing `PartnerContext`/Hibernate resolver, and every endpoint returns
the standard `ApiResponse<T>` envelope.

---

## Scope

**In scope**
- Backend: customer **edit**, **KYC lifecycle transitions** with reason + history, **widened
  detail** response (masked BVN/NIN), **list filter + name search** (blind index).
- Frontend: Customers **list** (filter + search + paginate), **detail** (profile + KYC actions +
  history), **create**, **edit**.

**Out of scope / Deferred**
- **`DEF-1C-30` — Customer identity verification via `baas-ncube`.** Real BVN/NIN verification
  against NIBSS/CBN, `KycLevel` progression driven by the verification result, and retry/failure
  handling. This track *captures* BVN/NIN and shows them masked; it does **not** verify them.
  Recorded in `docs/deferred-items.md`.
- BVN/NIN are **not editable** via the edit endpoint — changing an identity value is a
  verification event that belongs to the `DEF-1C-30` track.
- `externalReference` is immutable after create.

---

## Backend — `baas-engine`

Package `com.nubbank.baas.engine.customer`. All endpoints under the existing
`@RequestMapping("/baas/v1/customers")`, standard `ApiResponse<T>` envelope, tenant-scoped.

### 1. KYC state machine

States (existing `KycStatus` enum): `PENDING_KYC`, `ACTIVE`, `SUSPENDED`, `CLOSED`.

| Command | Endpoint | From → To |
|---------|----------|-----------|
| activate   | `POST /customers/{id}/activate`   | `PENDING_KYC → ACTIVE` |
| suspend    | `POST /customers/{id}/suspend`    | `ACTIVE → SUSPENDED` |
| reactivate | `POST /customers/{id}/reactivate` | `SUSPENDED → ACTIVE` |
| close      | `POST /customers/{id}/close`      | `ACTIVE | SUSPENDED → CLOSED` (terminal) |

- Request body: `{ "reason": "<non-blank>" }` (`@NotBlank`). Missing/blank → `400`.
- Illegal transition for the current status → `409` with error code `INVALID_KYC_TRANSITION`
  (message names the current status and the attempted command).
- Authorization: `@PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")` (the permission already
  exists in the V3 role catalogue — no new permission or Keycloak wiring).
- The transition and its history row are written in **one transaction**.
- Path-segment commands (not `?command=`) — explicit, one `@PreAuthorize` per command, clean in
  OpenAPI. Mirrors the platform's existing command style (card disputes).

### 2. `customer_kyc_events` table (new, tenant schema)

New Flyway migration `db/migration/tenant/V5__customer_kyc_events.sql`:

```sql
CREATE TABLE customer_kyc_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    from_status VARCHAR(50) NOT NULL,
    to_status   VARCHAR(50) NOT NULL,
    reason      TEXT NOT NULL,
    changed_by  VARCHAR(255),          -- operator principal from SecurityContext
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_kyc_events_customer ON customer_kyc_events(customer_id, changed_at DESC);
```

- Entity `CustomerKycEvent`; `CustomerKycEventRepository.findByCustomerIdOrderByChangedAtDesc(UUID)`.
- `changed_by` is the current operator (the SecurityContext principal — operator UUID for
  Keycloak tokens, partner id for first-party credentials).
- `GET /customers/{id}/kyc-events` → `List<CustomerKycEventResponse>` (newest first),
  `@PreAuthorize("hasAuthority('READ_CUSTOMER')")`.

### 3. Edit — `PUT /customers/{id}`

- `UpdateCustomerRequest`: `firstName`, `lastName`, `email`, `phone`, `dateOfBirth`, `gender`
  (the non-identity PII). `firstName`/`lastName` `@NotBlank`.
- Re-computes `name_search_tokens` (see §5) whenever the name changes.
- `@PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")`. Returns the widened `CustomerDetailResponse`.
- Not found → `404`.

### 4. Widened detail — `CustomerDetailResponse`

`GET /customers/{id}` returns a new `CustomerDetailResponse` (the **list** response keeps the
existing lean `CustomerResponse`, so the dashboard's `useRecentCustomers` is untouched):

```
id, externalReference, firstName, lastName, email,
phone, dateOfBirth, gender,
bvnMasked, ninMasked,          // "•••••••1234" — last 4 only; null when unset; full value NEVER returned
kycStatus, kycLevel,
createdAt, updatedAt
```

Masking helper lives in the service; decryption uses the existing field converter.

### 5. List filter + name search (blind index)

`GET /customers?page&size&kycStatus=&search=` (`@PreAuthorize("hasAuthority('READ_CUSTOMER')")`).

- **`kycStatus`** (optional) — exact-match filter on the plain `kyc_status` column.
- **`search`** (optional) — matches **either** a name-token prefix **or** an `externalReference`
  substring (`ILIKE`). Combined with `kycStatus` via AND.

**Why a blind index:** `first_name_encrypted`/`last_name_encrypted` are AES-encrypted with a
random IV, so SQL cannot `ILIKE` them. Searching encrypted names server-side requires a
deterministic, queryable derivative.

**`NameTokenizer`** (mirrors `baas-card`'s `PanHasher`): `HMAC-SHA256(key, message)` where the key
is `app.encryption.key` bytes (same key hierarchy as the field encryptor and card pan-hash — no
new secret). For a customer, the token set is:

```
for each word W in normalize(firstName) + normalize(lastName):     // lowercase, strip accents
    for len in 2 .. min(W.length, 12):
        token = HMAC-SHA256(key, W[0:len])
```

e.g. "John Doe" → HMAC of `jo, joh, john, do, doe`. (Cap: first 6 words; prefix length 2–12.)

**Storage:** `customers.name_search_tokens TEXT[]` (new column), **GIN-indexed**. Added in the
same `V5` migration. Computed in `CustomerService` on **create** and **update**.

**Query:** the search term is tokenized into words; each query word is hashed at its full length
and the row matches if `name_search_tokens @> ARRAY[<queryHashes>]` (contains **all** query-word
hashes → multi-word AND, prefix-capable because every stored prefix is itself a token), `OR`
`external_reference ILIKE '%' || :search || '%'`.

**Backfill:** tokens are written on **create** and **update**. The platform has essentially no
production customer data yet, so no bulk backfill is in scope for this track — any pre-existing
untokenized customer becomes name-searchable on its next edit. (A cross-schema reindex job is a
trivial future follow-up only if real untokenized data accumulates before then.)

**Security tradeoff (accepted, documented):** a blind index lets someone with raw DB access
observe that two customers share a name prefix (frequency analysis), but it is **never reversible
to the plaintext name without the server key**. Full names remain AES-encrypted; only one-way
HMACs are added. This is the standard searchable-encryption pattern for regulated data.

### 6. Error handling

| Condition | HTTP | Code |
|-----------|------|------|
| Illegal KYC transition for current status | 409 | `INVALID_KYC_TRANSITION` |
| Missing/blank `reason` on a transition | 400 | validation envelope |
| Customer not found (detail/update/transition) | 404 | `CUSTOMER_NOT_FOUND` |
| Update validation (e.g. bad email) | 400 | validation envelope |

All via the existing `BaasException` + `ApiResponse.error/fieldError` machinery.

---

## Frontend — `baas-backoffice`

Feature folder `src/features/customers/`. Two routes wired into `src/app/router.tsx` under
`RequireAuth → AppShell`, each wrapped in `RequireRoutePermission('READ_CUSTOMER')`:

| Route | Component |
|-------|-----------|
| `/customers` | `CustomersList` |
| `/customers/:id` | `CustomerDetail` |

### Components

- **`customers-list.tsx`** — paginated `DataTable` (Foundation primitive); a **KYC-status filter**
  (segmented control / select); a debounced **search box** (name or external ref); a **"New
  customer"** button (gated `CREATE_CUSTOMER`) opening the create modal; row click → detail. Empty
  and loading states.
- **`customer-detail.tsx`** — header (full name, KYC `StatusBadge`, external ref); **Overview**
  (contact + masked identity); **KYC actions** — buttons rendered **contextually** to current
  status (Activate only when `PENDING_KYC`; Suspend when `ACTIVE`; Reactivate when `SUSPENDED`;
  Close when `ACTIVE|SUSPENDED`; none when `CLOSED`), each opening the reason modal; an **Edit**
  button (gated `UPDATE_CUSTOMER`); a **KYC History** timeline.
- **`customer-form.tsx`** — shared create/edit form (React Hook Form + Zod), rendered inside the
  Foundation `CommandModal`. Create shows all fields incl. BVN/NIN; edit omits BVN/NIN +
  externalReference.
- **`kyc-action-modal.tsx`** — confirm modal capturing the required `reason` for a transition.
- **`kyc-history.tsx`** — timeline list of `from → to`, reason, who, when.

### Hooks — `use-customers.ts`

`useCustomers(params)`, `useCustomer(id)`, `useCustomerKycEvents(id)` (queries);
`useCreateCustomer()`, `useUpdateCustomer()`, `useKycTransition()` (mutations). Mutations
invalidate `qk.list('customers')`, `qk.detail('customers', id)`, and the kyc-events key on
success; errors surface through the global `MutationCache` toast. All calls go through
`useApiClient()` + `unwrapResult`/`extractPage`.

### Types

New paths/types hand-seeded into `src/api/schema.d.ts` (customer detail, update, kyc-transition,
kyc-events) until the next `npm run gen:api` against a live engine regenerates them — same
approach the dashboard/operator endpoints used.

---

## Data flow

1. List → `GET /customers?page&size&kycStatus&search` → `DataTable` rows (lean `CustomerResponse`).
2. Row → detail → `GET /customers/{id}` (widened) + `GET /customers/{id}/kyc-events`.
3. Create/Edit modal → `POST`/`PUT /customers` → invalidate list + detail.
4. KYC action modal → `POST /customers/{id}/{command}` `{reason}` → invalidate detail +
   kyc-events; the new status + new history row appear.

## Testing

**Engine (Testcontainers, `AbstractIntegrationTest`):**
- Each transition happy-path; illegal transition → `409`; blank reason → `400`.
- `kyc_events` recorded with correct `from/to/reason/changed_by`, newest-first ordering.
- Update mutates the allowed fields, re-tokenizes on name change, rejects unknown id (`404`).
- Widened detail returns masked BVN/NIN (never the full value).
- List: `kycStatus` filter; name search (prefix, multi-word AND); external-ref search;
  combined filter + search; tenant isolation (a second partner's customers never appear).

**Frontend (Vitest + RTL):** list renders + filter/search wiring; detail renders with
status-contextual actions; create/edit form validation; transition flow updates status + history;
history renders. **Playwright e2e:** list → create → open detail → activate → history shows the
event.

Both suites are required by the per-service SESSION COMPLETION GATE.

## Delivery (per the Branch & PR Discipline convention)

Two zero-overlap PRs, same pattern as #26/#27:
1. **Backend PR** — `baas-engine` customer endpoints (`feat/baas-engine-customer-lifecycle`).
2. **Frontend PR** — `baas-backoffice` Customers feature (`feat/baas-backoffice-customers`).

The frontend degrades gracefully if the backend isn't deployed yet (queries error → empty/error
states), so the PRs have no hard merge-order dependency. Each updates its own doc surface
(`docs/api-reference.html` for backend; `docs/backoffice-operations.md` for frontend) per gate
item 4.

## Verified assumptions

- `UPDATE_CUSTOMER` exists in `V3__role_catalogue.sql` — reused for edit + KYC transitions.
- `app.encryption.key` is the established HMAC key (used by `FieldEncryptor` + card `PanHasher`)
  — reused by `NameTokenizer`.
- `KycStatus` = `PENDING_KYC | ACTIVE | SUSPENDED | CLOSED` (no `REJECTED`/`WITHDRAWN` in this
  product) — the four-command state machine covers the full enum.
