# Granular Partner RBAC — Design Spec

**Date:** 2026-06-18
**Status:** Approved (design) — ready for implementation plan
**Closes:** DEF-1C-15 (granular RBAC for HMAC partner-login users)
**Enables:** Spec B — Maker-Checker / Four-Eyes (a meaningful approver role requires this)

---

## 1. Problem & context

Today every **first-party** partner principal — both human partner users (JWT, `authMode="JWT"`) and API keys (`authMode="API_KEY"`) — is granted **blanket full authority** over its tenant:

```
PartnerContextFilter (else branch) → AuthorityResolver.fullTenantAuthorities() → PermissionRepository.findAllCodes()
```

Two consequences:

1. **No least-privilege for partners.** A partner cannot create a read-only or teller-scoped user/key; every credential can do everything (open accounts, move money, manage keys).
2. **Partner orgs have exactly one user.** `AuthController.register()` mints a single `PARTNER_ADMIN` user; there is **no** endpoint to add more partner users or assign roles. So partner-side four-eyes is currently impossible — there is no second person to be a checker.

The granular RBAC machinery already exists and is battle-tested on the **operator** (Keycloak / NubBank staff) path: tenant-schema `roles` / `permissions` / `role_permissions` / `user_roles`, resolved by `AuthorityResolver.operatorAuthorities(userId)` → `UserRoleRepository.findPermissionCodesByUserId(userId)`. This spec extends that machinery to first-party partner principals; it does **not** build a new RBAC system.

---

## 2. Goals / Non-goals

**Goals**
- A partner admin can create/manage additional partner users within their own org and assign them scoped roles.
- A small set of seeded built-in partner roles, **plus** partner-defined custom roles (hybrid).
- API keys become least-privilege (scoped), not blanket-full.
- Exactly **one** path to full authority in the system: holding the `PARTNER_ADMIN` superuser-marker role — visible, grantable, revocable data.
- No behaviour change for existing partners until they opt into scoping (back-compat via an explicit one-time backfill).

**Non-goals**
- Operator (Keycloak) RBAC — unchanged.
- The maker-checker workflow itself — that is Spec B; this spec only provides the role vocabulary (a distinct approver role) it needs.
- Identity verification, MFA, password-policy hardening — out of scope.

---

## 3. Decisions (from brainstorming)

| # | Decision |
|---|----------|
| D1 | **Self-serve**: a partner *admin* manages users/roles within their own org. NubBank staff are not in the routine loop. |
| D2 | **Hybrid roles**: seeded built-in roles + partner-defined custom roles. |
| D3 | **API keys are scoped too** (least-privilege); existing keys grandfathered to full. |
| D4 | **`PARTNER_ADMIN` always full** — implemented as a *dynamic superuser marker* (§6), not a frozen bundle. |
| D5 | **Per-request DB resolution** (same as operators) — role/scope changes and revocation take effect on the next call, never frozen into the JWT. |
| D6 | **Architecture: reuse the tenant-schema RBAC tables** (Approach 1) — partner users keyed in `user_roles` by their `public.partner_users` UUID, exactly as operators are keyed by external UUID. |
| D7 | **Deny by default**: no role grants → no authority. The blanket `fullTenantAuthorities()` fallback is removed (§6). |

---

## 4. Data model

All RBAC tables remain **tenant-schema** (per-partner, so each partner can have custom roles). Changes:

### 4.1 `roles` (tenant) — new columns
| Column | Type | Purpose |
|--------|------|---------|
| `built_in` | `BOOLEAN NOT NULL DEFAULT false` | Protects from edit/delete. |
| `role_scope` | `VARCHAR(20) NOT NULL DEFAULT 'PARTNER'` | `PARTNER` \| `OPERATOR` \| `SHARED`. Keeps partner-assignable roles separate from operator roles in the shared table. Partner admins only ever see/manage `PARTNER`-scoped roles. |
| `is_superuser` | `BOOLEAN NOT NULL DEFAULT false` | The dynamic full-authority **marker** (§6). Set on exactly one seeded role: `PARTNER_ADMIN`. Unambiguous — no name string-matching. |

Existing operator roles are migrated to `role_scope='OPERATOR'` (or `SHARED` if already used by both).

### 4.2 Seeded built-in `PARTNER`-scoped roles (per tenant)
| Role name | `is_superuser` | Permission bundle |
|-----------|:---:|-------------------|
| `PARTNER_ADMIN` | **true** | (marker — resolves to **all** permissions dynamically; no `role_permissions` rows) |
| `PARTNER_MAKER` | false | `READ_*`, `CREATE_ACCOUNT`, `DEPOSIT`, `WITHDRAW`, `CREATE_CUSTOMER`, `CREATE_LOAN`, `INITIATE_PAYMENT` — operate, **not** approve |
| `PARTNER_APPROVER` | false | `READ_*` + the future `APPROVE_*` permissions (Spec B) — the "checker" |
| `PARTNER_VIEWER` | false | `READ_*` only |

(Exact bundle codes are finalised against the live `permissions` catalogue in the plan.)

### 4.3 New seeded permissions (tenant `permissions`)
- `MANAGE_PARTNER_USERS` — gates partner-user management endpoints.
- `MANAGE_ROLES` — gates role CRUD.

Both are in the `PARTNER_ADMIN` dynamic-full set automatically (they are in `findAllCodes()`).

### 4.4 `partner_api_keys` (public) — reuse existing `scopes` JSONB
No new column. The existing `scopes JSONB` (currently defaults to `"[]"`) becomes the key's granted permission set:
- `["*"]` → dynamic full (the grandfather value and the "admin key" value).
- `["READ_ACCOUNT","READ_CUSTOMER", ...]` → exactly those codes.
- `[]` → no authority (deny).

At issuance the admin picks a **role** (or an explicit code list); the chosen role's *current* permission codes are snapshotted into `scopes` (full/admin role stored as the `["*"]` sentinel so it stays dynamic). Snapshot semantics are deliberate for M2M stability; a "refresh scopes" re-issue can re-snapshot. (See §10 for the tradeoff note.)

### 4.5 `partner_users` (public) — no new columns
Role assignments live in tenant `user_roles`, keyed by the `partner_users` UUID — identical to how operators are keyed.

### 4.6 `PartnerContext` — carry the API-key principal id
For `authMode="API_KEY"`, `PartnerContext.userId` carries the authenticated **API key's UUID** (the principal), so `apiKeyAuthorities` can load the key and read `scopes`. (For `authMode="JWT"`, `userId` already carries the partner-user UUID via the JWT `sub`.)

---

## 5. API surface

All endpoints are **org-scoped to the caller's tenant** (PartnerContext). Other-org IDs return **404** (enumeration-safe, matching the existing pattern).

### 5.1 Partner-user management — gated `@PreAuthorize("hasAuthority('MANAGE_PARTNER_USERS')")`
| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/baas/v1/partner-users` | Create user in caller's org. Body: `email`, `password` (or invite), `roleIds[]` (**must be non-empty** — §6 trap closure). |
| `GET` | `/baas/v1/partner-users` | List users in caller's org (with roles). |
| `GET` | `/baas/v1/partner-users/{id}` | Detail. |
| `PUT` | `/baas/v1/partner-users/{id}/roles` | Replace role assignments (replace-all). |
| `POST` | `/baas/v1/partner-users/{id}/deactivate` · `/reactivate` | Soft enable/disable. |

### 5.2 Roles — gated `@PreAuthorize("hasAuthority('MANAGE_ROLES')")`, scoped to `PARTNER` roles
| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/baas/v1/roles` | List the tenant's `PARTNER`-scoped roles (built-in + custom). |
| `POST` | `/baas/v1/roles` | Create a custom `PARTNER` role (permission codes). |
| `PUT` | `/baas/v1/roles/{id}` | Edit a custom role (built-in → 409). |
| `DELETE` | `/baas/v1/roles/{id}` | Delete a custom role (built-in → 409; still-assigned → 409). |
| `GET` | `/baas/v1/permissions` | The assignable permission catalogue (for the role editor). |

(The existing operator-facing `RoleController` is extended/scoped by `role_scope`; partners never see `OPERATOR` roles.)

### 5.3 API-key issuance (existing endpoint, extended)
The key-issuance request gains a required scope: a `roleId` (snapshotted) **or** explicit `scopes[]`. Existing keys are grandfathered (§7).

---

## 6. Authority resolution (the core change)

`PartnerContextFilter`, after `PartnerContext` is set, branches on `authMode`:

| `authMode` | Resolution |
|------------|------------|
| `OPERATOR_JWT` | `operatorAuthorities(userId)` — **unchanged** |
| `JWT` (partner user) | `partnerUserAuthorities(userId)` |
| `API_KEY` | `apiKeyAuthorities(apiKeyId)` |

The old `else → fullTenantAuthorities()` branch is **deleted**.

### 6.1 `partnerUserAuthorities(UUID partnerUserId)`
```
if user holds any role with is_superuser = true   →  permissionRepo.findAllCodes()   // dynamic full
else                                               →  userRoleRepo.findPermissionCodesByUserId(partnerUserId)
```
The `else` is the **same query operators already use**. Empty result → empty authorities → `AuthEnforcementFilter` denies.

### 6.2 `apiKeyAuthorities(UUID apiKeyId)`
Load the key, parse `scopes`:
```
if scopes contains "*"   →  permissionRepo.findAllCodes()   // dynamic full
else                     →  scopes (the explicit codes)
```
Empty `scopes` → deny.

### 6.3 Why this satisfies the two subtle properties

**(A) "no grants → deny," not "→ full" (Insight 1).** Resolution is purely additive: authority = union of explicit grants, with **no fallback**. Zero grants ⇒ zero authority ⇒ deny — structurally, nothing can grant god-mode by accident. The mgmt API forbids creating a grant-less user (`roleIds` non-empty), so the deny rule only ever bites a user whose roles were *deliberately* all removed (correct) — never a half-configured new user.

**(B) `PARTNER_ADMIN` dynamic marker (Insight 2).** Full authority is `findAllCodes()` computed **per request** against the live `permissions` table, gated behind the `is_superuser` marker role. When a new module's migration inserts permission codes per tenant, admins (and `["*"]` keys) include them on the very next call — no `role_permissions` re-seed, ever.

**The unifying observation:** `findAllCodes()` is **not deleted** — it is *demoted* from "the default for all first-party principals" to "the private implementation of the `PARTNER_ADMIN` marker (and the `["*"]` key sentinel)." There is exactly **one** path to full authority, and it is auditable, grantable, and revocable data.

---

## 7. Provisioning, backfill & safe cutover

### 7.1 New tenants
`TenantProvisioningService` seeds, into each new tenant schema: the new permissions (`MANAGE_PARTNER_USERS`, `MANAGE_ROLES`), the four built-in `PARTNER` roles (with `built_in=true`, `role_scope='PARTNER'`, and `is_superuser=true` on `PARTNER_ADMIN`), and their `role_permissions` bundles. `AuthController.register()` then assigns the new admin user a `user_roles` row → `PARTNER_ADMIN`. So "admin = full" is *data*, not a hardcoded branch.

### 7.2 Existing tenants — one-time idempotent backfill (app code)
A per-schema SQL migration **cannot** resolve which `public` users/keys belong to it, so the backfill runs in application code: iterate `PartnerOrganization`s; per org, set `PartnerContext` to its schema, ensure the built-in roles/permissions exist, then:
- assign every existing `PartnerUser` the `PARTNER_ADMIN` role (preserve their implicit full → explicit full), and
- set every existing `PartnerApiKey.scopes = ["*"]` (preserve full).

Idempotent (skip already-present grants/scopes). This converts today's implicit full into explicit grants so nothing breaks; the fallback is then needed by no one.

### 7.3 Safe cutover (no lockout window)
The strict resolver must not serve traffic before the backfill completes. Two options, chosen by tenant count:
- **Blocking startup backfill (recommended initially):** the reconciliation runs once at boot, tracked by a marker, and completes **before** the app serves requests. Strict resolution is then safe from request #1; idempotency makes a crash mid-run resume-safe.
- **Per-tenant lazy marker (if tenant count grows):** an un-backfilled tenant transparently uses legacy-full *for that request only* and is backfilled on first touch. The legacy fallback is conditioned on "tenant not yet backfilled," so it can never reach a **new** principal (new tenants are seeded at provisioning; new users always carry ≥1 grant).

---

## 8. Guardrails & edge cases

| Case | Behaviour |
|------|-----------|
| **Privilege escalation** | You may only create/assign a role whose permission set ⊆ your own current authority. (`PARTNER_ADMIN` = all, so unrestricted within `PARTNER` roles; a delegated `MANAGE_*` non-admin cannot grant what it lacks.) |
| **Last-admin lockout** | Cannot remove the final active `PARTNER_ADMIN` of an org, nor self-deactivate as the last admin. |
| **Cross-org isolation** | Manage only your own org's users/keys/roles; other-org IDs → 404. |
| **Built-in role mutation** | `built_in` roles: edit/delete → 409. |
| **Custom role in use** | Delete while assigned to any user/key → 409 (block; never silently orphan). |
| **Grant-less user** | Creation with empty `roleIds` → 400. A user later stripped of all roles → denied (correct). |
| **Operator/partner separation** | Partner admins see/assign only `role_scope='PARTNER'` roles; operator roles invisible. |

---

## 9. Testing strategy

**Unit (`AuthorityResolver`)**
- `partnerUserAuthorities`: superuser-marker user → all codes (and dynamically includes a freshly-inserted permission); maker → its subset; viewer → read-only; unassigned → empty.
- `apiKeyAuthorities`: `["*"]` → all (dynamic); explicit scopes → those codes; `[]` → empty.

**Integration (Testcontainers, real schema)**
- A `PARTNER_MAKER` user opens an account ✅; a `PARTNER_VIEWER` gets **403** on the same call (proves enforcement bites).
- A read-only API key → **403** on `CREATE_ACCOUNT`; a `["*"]` key → 200.
- Privilege-escalation attempt (assign a role exceeding own authority) → blocked.
- Last-admin lockout → blocked.
- Cross-org user/role access → **404**.
- New-tenant provisioning seeds the four roles + assigns admin; existing-tenant backfill assigns existing user + key full and is idempotent on a second run.
- Regression: an operator (`OPERATOR_JWT`) path is unchanged.

---

## 10. Deferrals / open items

- **Key scope snapshot vs live (D3):** keys store a *snapshot* of permission codes (M2M stability); editing the source role does not retro-update issued keys. A "refresh scopes" re-issue is the escape hatch. Accepted; revisit if partners need live-tracking keys.
- **Authority caching:** `findAllCodes()` / `findPermissionCodesByUserId` run per request. Acceptable now; per-tenant cache is DEF-1C-18 (Phase 2).
- **Orphaned-grant guard:** `user_roles` rows reference a `public.partner_users` UUID with no hard cross-schema FK (same as operators). Reconciliation sweep is DEF-1C-08.
- **Invite/email flow:** initial-credential delivery is admin-set password in v1; email-invite is a later nicety (needs email infra).
- **Model C (licensed bank) NubBank-mediated provisioning:** out of scope here; self-serve (D1) assumed.

---

## 11. Relationship to Spec B (Maker-Checker)

Spec B builds directly on this:
- `PARTNER_MAKER` vs `PARTNER_APPROVER` give four-eyes its two distinct principals.
- The future `APPROVE_*` permissions (referenced in the `PARTNER_APPROVER` bundle) are *defined* in Spec B; this spec only reserves the role to carry them.
- The "checker ≠ maker" rule is meaningful only because a partner can now have ≥2 users with different roles — which is exactly what this spec delivers.
