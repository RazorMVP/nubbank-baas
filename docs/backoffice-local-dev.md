# Running `baas-backoffice` against a local `baas-engine`

By default `npm run dev` serves the backoffice with **dev auth** and no real backend, so
data-driven views (Accounts, Customers, …) are empty. To exercise them against a live
engine on your machine, follow this guide.

Two facts shape the setup:

1. **The engine has no CORS config in Phase 1C.** A browser calling the engine cross-origin
   (`:3001` → `:8080`) would be blocked. We avoid this with a **Vite dev proxy**: leave
   `VITE_API_BASE_URL` empty so the app makes same-origin (relative) requests, and Vite
   forwards `/baas/**` to the engine. The proxy lives in `vite.config.ts`; override its
   target with `VITE_ENGINE_ORIGIN` (default `http://localhost:8080`).
2. **The engine has no `dev-token` bypass.** The frontend's placeholder `dev-token` is not a
   valid JWT and the engine rejects it (401). You must hand the dev-auth provider a **real
   partner JWT** via `VITE_DEV_TOKEN`.

---

## 1. Start Postgres + Redis

```bash
docker run -d --name baas-pg \
  -e POSTGRES_DB=nubbank_baas -e POSTGRES_USER=baas -e POSTGRES_PASSWORD=baas \
  -p 127.0.0.1:5432:5432 postgres:16-alpine
docker run -d --name baas-redis -p 127.0.0.1:6379:6379 redis:7-alpine
```

> If `:5432`/`:6379`/`:8080` are taken on your machine, pick free host ports and adjust the
> engine env (`DATASOURCE_URL`, `REDIS_PORT`, `SERVER_PORT`) and `VITE_ENGINE_ORIGIN` to match.

## 2. Start the engine (Java 21)

The engine has **no datasource/secret defaults** — it fails fast if they are unset.

```bash
cd baas-engine
export DATASOURCE_URL=jdbc:postgresql://localhost:5432/nubbank_baas
export DATASOURCE_USERNAME=baas
export DATASOURCE_PASSWORD=baas
export JWT_SECRET="$(openssl rand -base64 48)"           # >= 32 chars (HS256)
export ENCRYPTION_KEY="$(openssl rand -base64 48)"        # any length (SHA-256-derived)
export INTERNAL_SERVICE_SECRET="$(openssl rand -base64 48)"
./mvnw spring-boot:run
# health: curl -s http://localhost:8080/actuator/health
```

## 3. Register a partner → get a JWT

The engine is multi-tenant: each partner gets its own schema, provisioned asynchronously
on registration. The returned token's `schema_name` claim routes all tenant queries.

```bash
curl -s -X POST http://localhost:8080/baas/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"orgName":"Demo Partner","adminEmail":"admin@demo.local","password":"DemoPassword123!"}'
# copy .data.token from the response
```

## 4. (Optional) Seed a customer + account

```bash
TOKEN=<paste .data.token>
CID=$(curl -s -X POST http://localhost:8080/baas/v1/customers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"firstName":"John","lastName":"Doe","email":"john@demo.local","phone":"+2348030000001"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])')

curl -s -X POST http://localhost:8080/baas/v1/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CID\",\"accountTypeLabel\":\"CHECKING\",\"accountName\":\"Main\",\"currencyCode\":\"NGN\",\"minimumBalance\":0,\"openingDeposit\":50000}"
```

## 5. Point the backoffice at the engine and run it

In `baas-backoffice/.env` (copy from `.env.example`):

```bash
VITE_API_BASE_URL=                       # empty → relative requests → dev proxy
VITE_ENGINE_ORIGIN=http://localhost:8080 # proxy target (match your engine port)
VITE_DEV_AUTH=true
VITE_DEV_TOKEN=<paste the partner JWT from step 3>
VITE_DEV_AUTHORITIES=READ_CUSTOMER,CREATE_CUSTOMER,UPDATE_CUSTOMER,READ_ACCOUNT,CREATE_ACCOUNT,UPDATE_ACCOUNT,DEPOSIT,WITHDRAW,READ_LOAN,CREATE_LOAN,APPROVE_LOAN,DISBURSE_LOAN,INITIATE_PAYMENT,RUN_REPORT
```

> `.env` is gitignored. You can also pass these as shell env vars — Vite gives shell vars
> priority over `.env` for `VITE_`-prefixed keys:
> `VITE_API_BASE_URL='' VITE_DEV_TOKEN="$TOKEN" npm run dev`.

```bash
cd baas-backoffice
npm run dev   # http://localhost:3001
```

Open <http://localhost:3001/accounts> — the seeded data renders, and the lifecycle actions
(Deposit / Withdraw / Freeze / Unfreeze / Close) work against the live engine. The visible
lifecycle buttons require the `UPDATE_ACCOUNT` authority (already in the dev list above).

---

## Notes & caveats

- **`VITE_DEV_AUTHORITIES` is dev-only UI gating.** The engine enforces authority
  independently from the JWT/role. A first-party partner JWT (`PARTNER_ADMIN`) gets full
  tenant authority in 1C, so all account commands are permitted.
- **Tokens expire after 24h.** Re-register (or add a login endpoint call) to refresh.
- **Production** sets `VITE_API_BASE_URL` to the real API origin (absolute), bypassing the
  proxy; the dev proxy only affects `npm run dev`, never `vite build`.
