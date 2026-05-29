# Phase 1C — Cross-Track Interface Contracts

Foundation publishes these so parallel tracks build against stable shapes.

## 1. Operator authority claim format (Backoffice, all backend tracks)
- Operators authenticate via Keycloak realm `baas-partner-{uuid}`; token `iss` = realm issuer URL.
- The issuer is registered on `public.partner_organizations.keycloak_issuer` (unique); only partners
  whose status `isActiveForAuth()` (SANDBOX/BASIC/PRO/ENTERPRISE, not SUSPENDED/PENDING_REVIEW) resolve.
- `sub` = operator UUID; it is the join key to tenant-schema `user_roles.user_id` (no FK — see spec §6.2.4).
- Spring authorities = `permissions.code` values granted to the operator's roles (authMode `OPERATOR_JWT`).
- First-party credentials (API key, HMAC partner-login JWT) carry the **full** tenant authority set
  (authMode `API_KEY` / `JWT`). Granular RBAC for HMAC users is deferred (DEF-1C-15).
- `@PreAuthorize("hasAuthority('<CODE>')")` is the gate; denials return HTTP 403 with the standard
  `{ "data": null, "errors": [{ "code": "ACCESS_DENIED", ... }] }` envelope (via GlobalExceptionHandler).

## 2. BIN-lookup contract (Card → FEP)
- `baas-card` exposes `GET /internal/v1/bins/{bin}` (HMAC inter-service auth) →
  `{ "partnerId": "<uuid>", "schemaName": "partner_<uuid>" }` or 404.
- `baas-fep` calls this after extracting DE2 PAN → 6/8-digit BIN; caches 5 min (Caffeine).
- Unknown BIN → FEP returns ISO 8583 RC `91`, no PAN echo.

## 3. Admin namespace reservation (Custodian, Platform-Admin)
- `/baas-admin/v1/**` is reserved for the NubBank admin chain.
- Foundation scoped the partner chain to `@Order(2)` + `securityMatcher("/baas/v1/**", "/actuator/**",
  "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")` (bean `partnerFilterChain`).
- The Custodian track adds `@Order(1) SecurityFilterChain adminFilterChain` with
  `securityMatcher("/baas-admin/v1/**")`, routed to the `baas_readonly_admin` datasource.
- NOTE for the Custodian track: `RateLimitFilter` carries a servlet-`Filter` `@Order(1)` — this is a
  SEPARATE ordering namespace from `SecurityFilterChain` `@Order`. They do not collide, but when adding
  the `@Order(1)` admin chain, add a clarifying comment to `RateLimitFilter`'s `@Order` so the two
  `Order(1)`s are never confused.
- Admin JWTs carry NO `partner_id`; the admin-issuer (`app.keycloak.admin-issuer`) is recognised by
  `OperatorJwtResolver.isAdminIssuer(...)` and is rejected on the partner API (no PartnerContext set →
  401 via AuthEnforcementFilter).
