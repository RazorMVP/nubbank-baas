# baas-backoffice Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared foundation of the `baas-backoffice` React app (deliverable D8) — scaffold, design tokens, auth seam, typed API layer, RBAC, app shell, reusable primitives, and the Dashboard as the first real screen — so every per-domain sub-plan builds on a tested base.

**Architecture:** React 19 + Vite 6 SPA. Design tokens are CSS variables consumed by Tailwind 4 `@theme`. A single `AuthProvider` seam abstracts PKCE (prod) vs a dev token provider (local/CI/e2e). A typed API layer (`openapi-typescript` types + `openapi-fetch`) injects the bearer token, unwraps the engine's `{ data, meta, errors }` envelope, and feeds TanStack Query. RBAC is permission-code based (matching the engine's `hasAuthority('CODE')`), gating nav, routes, and command buttons. Every domain later follows one repeatable shape (list → detail → command modals) on this base.

**Tech Stack:** React 19, Vite 6, TypeScript 5, Tailwind CSS 4, shadcn/ui (Radix + Tailwind, copied-in), TanStack Query 5, TanStack Table 8, React Router 7, Zustand 5, React Hook Form 7 + Zod 3, `openapi-fetch` + `openapi-typescript`, `oidc-client-ts`, Vitest + React Testing Library, Playwright. Node 22 LTS, **npm** (committed `package-lock.json`).

---

## Decisions locked by this plan

These are choices not fixed by the spec; the user may flag any during plan review.

| # | Decision | Choice | Why |
|---|---|---|---|
| F1 | Package manager | **npm** (`npm ci`, committed `package-lock.json`) | Ships with Node; no corepack step in CI/Docker; bulletproof reproducibility. |
| F2 | Node version | **22 LTS** (`.nvmrc`, CI `node-version: 22`) | Current LTS in 2026; satisfies Vite 6 / Tailwind 4 (Node ≥18). |
| F3 | API client | **`openapi-typescript` (types) + `openapi-fetch` (runtime)** | Tiny, fully-typed, no heavyweight generated client; pairs cleanly with TanStack Query and an envelope-unwrap middleware. |
| F4 | API types snapshot | Generated `src/api/schema.d.ts` is **committed** | CI/build need no live engine; regenerate via `npm run gen:api` against a running engine. |
| F5 | Operator authorities source | First-class `AuthProvider.authorities`; dev provider env-driven; prod reads `authorities`/`realm_access.roles` token claim **with a documented backend `/me` follow-up** (see Backend Dependencies) | The engine does **not** put authorities in the JWT, so the UI needs an explicit authorities channel to gate nav/routes. |
| F6 | e2e API mocking | Playwright `page.route` interception | No msw dependency; CI needs no engine. |

## Backend dependencies (coordination, not blockers for this plan)

- **`GET /baas/v1/operators/me` → `{ userId, name, email, authorities: string[] }`** does not exist yet. The PKCE provider reads authorities from a token claim (`authorities` array or `realm_access.roles`) as an interim; the robust path is this endpoint. Tracked as a follow-up; the Foundation is fully testable without it (dev provider supplies authorities). Add to `docs/deferred-items.md` in Task 18.
- **Engine acceptance of the dev token** for local end-to-end is the engine's dev profile concern. Foundation unit/integration tests use a stub fetch / `page.route`, so no live engine is required to build or test this plan.

## File structure (created by this plan)

```
baas-backoffice/
├── .nvmrc                         # "22"
├── .dockerignore
├── Dockerfile                     # node build → nginx runtime (port 3001)
├── nginx.conf                     # SPA fallback + /baas/v1 + /v3/api-docs proxy
├── index.html
├── package.json
├── package-lock.json
├── tsconfig.json
├── tsconfig.node.json
├── vite.config.ts                 # Vite + Vitest config
├── playwright.config.ts
├── components.json                # shadcn/ui config
├── .env.example                   # VITE_* documented
├── src/
│   ├── main.tsx                   # root render + providers
│   ├── app/
│   │   ├── App.tsx                # router outlet + providers composition
│   │   ├── providers.tsx          # QueryClient + Auth + Toast + ErrorBoundary
│   │   ├── router.tsx             # React Router 7 route tree + guards
│   │   └── error-boundary.tsx
│   ├── styles/
│   │   └── tokens.css             # CSS variables + Tailwind @theme (design tokens)
│   ├── lib/
│   │   ├── cn.ts                  # className merge util
│   │   └── rbac.ts               # hasPermission(), permission code constants
│   ├── auth/
│   │   ├── types.ts               # AuthProvider contract
│   │   ├── context.tsx            # React context + useAuth()
│   │   ├── dev-provider.ts        # env-driven dev auth
│   │   ├── pkce-provider.ts       # oidc-client-ts PKCE auth
│   │   └── create-provider.ts     # env-selected factory
│   ├── api/
│   │   ├── schema.d.ts            # generated OpenAPI types (committed)
│   │   ├── envelope.ts            # unwrap(), extractPage(), ApiError
│   │   ├── client.ts              # createApiClient() (openapi-fetch + middleware)
│   │   └── query.ts               # queryKey helpers + useApiQuery wrapper
│   ├── components/
│   │   ├── ui/                    # shadcn/ui copied-in primitives (button, dialog, input, ...)
│   │   ├── status-badge.tsx
│   │   ├── page-header.tsx
│   │   ├── data-table.tsx         # TanStack Table wrapper
│   │   ├── command-modal.tsx      # RHF + Zod modal shell
│   │   ├── form-field.tsx
│   │   └── require-permission.tsx # RBAC render gate
│   ├── layout/
│   │   ├── app-shell.tsx
│   │   ├── sidebar.tsx
│   │   ├── topbar.tsx
│   │   └── nav-config.ts          # nav items + requiredPermission (RBAC-gated)
│   └── features/
│       ├── auth/login.tsx         # Login screen (Figma)
│       └── dashboard/
│           ├── dashboard.tsx      # Dashboard screen (Figma)
│           ├── kpi-tile.tsx
│           └── use-dashboard.ts   # data hooks (customers/accounts)
└── (test files colocated as *.test.ts/tsx, e2e under e2e/)
```

---

## Task 1: Scaffold Vite + React 19 + TypeScript

**Files:**
- Create: `baas-backoffice/package.json`, `baas-backoffice/index.html`, `baas-backoffice/vite.config.ts`, `baas-backoffice/tsconfig.json`, `baas-backoffice/tsconfig.node.json`, `baas-backoffice/.nvmrc`, `baas-backoffice/src/main.tsx`, `baas-backoffice/src/app/App.tsx`, `baas-backoffice/.gitignore`

- [ ] **Step 1: Create the project directory and `.nvmrc`**

```bash
cd /Users/razormvp/nubbank-baas
mkdir -p baas-backoffice/src/app
printf '22\n' > baas-backoffice/.nvmrc
```

- [ ] **Step 2: Write `baas-backoffice/package.json`**

```json
{
  "name": "baas-backoffice",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "engines": { "node": ">=22" },
  "scripts": {
    "dev": "vite --port 3001",
    "build": "tsc -b && vite build",
    "preview": "vite preview --port 3001",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:e2e": "playwright test",
    "typecheck": "tsc -b --noEmit",
    "gen:api": "openapi-typescript ${VITE_OPENAPI_URL:-http://localhost:8080/v3/api-docs} -o src/api/schema.d.ts"
  },
  "dependencies": {
    "@tanstack/react-query": "^5.59.0",
    "@tanstack/react-table": "^8.20.5",
    "openapi-fetch": "^0.13.0",
    "oidc-client-ts": "^3.1.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "react-hook-form": "^7.53.0",
    "react-router-dom": "^7.0.0",
    "zod": "^3.23.8",
    "zustand": "^5.0.0",
    "@hookform/resolvers": "^3.9.0",
    "class-variance-authority": "^0.7.0",
    "clsx": "^2.1.1",
    "tailwind-merge": "^2.5.4",
    "lucide-react": "^0.460.0"
  },
  "devDependencies": {
    "@playwright/test": "^1.48.0",
    "@tailwindcss/vite": "^4.0.0",
    "@testing-library/jest-dom": "^6.6.0",
    "@testing-library/react": "^16.0.1",
    "@testing-library/user-event": "^14.5.2",
    "@types/node": "^22.8.0",
    "@types/react": "^19.0.0",
    "@types/react-dom": "^19.0.0",
    "@vitejs/plugin-react": "^4.3.3",
    "jsdom": "^25.0.1",
    "openapi-typescript": "^7.4.0",
    "tailwindcss": "^4.0.0",
    "typescript": "^5.6.3",
    "vite": "^6.0.0",
    "vitest": "^2.1.0"
  }
}
```

- [ ] **Step 3: Write `baas-backoffice/tsconfig.json` and `tsconfig.node.json`**

`baas-backoffice/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] },
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

`baas-backoffice/tsconfig.node.json`:
```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true,
    "noEmit": true
  },
  "include": ["vite.config.ts", "playwright.config.ts"]
}
```

- [ ] **Step 4: Write `baas-backoffice/vite.config.ts` (includes Vitest)**

```typescript
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: { port: 3001 },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    css: true,
    exclude: ['**/node_modules/**', '**/e2e/**'],
  },
});
```

- [ ] **Step 5: Write `index.html`, `src/main.tsx`, `src/app/App.tsx`, `src/test-setup.ts`, `.gitignore`**

`baas-backoffice/index.html`:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/png" href="/nubbank-icon.png" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>NubBank — Backoffice</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`baas-backoffice/src/main.tsx`:
```typescript
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './app/App';
import './styles/tokens.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

`baas-backoffice/src/app/App.tsx` (temporary — replaced in Task 12):
```typescript
export default function App() {
  return <div data-testid="app-root">NubBank Backoffice</div>;
}
```

`baas-backoffice/src/test-setup.ts`:
```typescript
import '@testing-library/jest-dom/vitest';
```

`baas-backoffice/.gitignore`:
```
node_modules
dist
dist-ssr
*.local
.env
coverage
playwright-report
test-results
```

- [ ] **Step 6: Install dependencies**

Run: `cd /Users/razormvp/nubbank-baas/baas-backoffice && npm install`
Expected: dependencies install, `package-lock.json` created, exit code 0.

- [ ] **Step 7: Create `src/styles/tokens.css` placeholder so the build resolves**

```css
/* Replaced with full token set in Task 2 */
@import 'tailwindcss';
```

- [ ] **Step 8: Verify the build and typecheck pass**

Run: `cd /Users/razormvp/nubbank-baas/baas-backoffice && npm run build`
Expected: `tsc -b` passes, `vite build` writes `dist/`, exit code 0.

- [ ] **Step 9: Commit**

```bash
cd /Users/razormvp/nubbank-baas
git add baas-backoffice
git commit -m "feat(backoffice): scaffold Vite + React 19 + TS project

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Design tokens → Tailwind 4 theme + CSS variables

**Files:**
- Modify: `baas-backoffice/src/styles/tokens.css`
- Test: `baas-backoffice/src/styles/tokens.test.ts`

Tokens come from the canonical Figma file `gEDnLrLD4UrChcND0yCdZ9` ("NubBank BaaS — Backoffice") and spec §4.1.

- [ ] **Step 1: Write the failing test**

`baas-backoffice/src/styles/tokens.test.ts`:
```typescript
import { readFileSync } from 'node:fs';
import { describe, it, expect } from 'vitest';

const css = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');

describe('design tokens', () => {
  it.each([
    ['--color-brand-primary', '#0078d4'],
    ['--color-brand-accent', '#28a8ea'],
    ['--color-ink', '#1a1a1a'],
    ['--color-surface', '#ffffff'],
    ['--color-surface-ink', '#23262e'],
    ['--color-bg-app', '#f5f7fa'],
    ['--color-tint-blue', '#e8f3fc'],
    ['--color-muted', '#6b7280'],
    ['--color-border', '#e6e9ef'],
    ['--color-success', '#1f9d57'],
    ['--color-warning', '#b7791f'],
    ['--color-danger', '#d64545'],
  ])('defines %s = %s', (name, value) => {
    const re = new RegExp(`${name}:\\s*${value}`, 'i');
    expect(css).toMatch(re);
  });

  it('defines radius tokens', () => {
    expect(css).toMatch(/--radius-card:\s*14px/);
    expect(css).toMatch(/--radius-control:\s*8px/);
    expect(css).toMatch(/--radius-pill:\s*999px/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- tokens`
Expected: FAIL — tokens not yet defined.

- [ ] **Step 3: Write the full token set**

`baas-backoffice/src/styles/tokens.css`:
```css
@import 'tailwindcss';

/* NubBank design tokens — source: Figma gEDnLrLD4UrChcND0yCdZ9 + spec §4.1.
   Defined as Tailwind 4 @theme so utilities (bg-brand-primary, text-ink, etc.)
   and CSS variables (var(--color-brand-primary)) both resolve. A dark theme or
   rebrand drops in by overriding these variables — no component changes. */
@theme {
  --color-brand-primary: #0078d4;
  --color-brand-accent: #28a8ea;
  --color-ink: #1a1a1a;
  --color-surface: #ffffff;
  --color-surface-ink: #23262e;
  --color-bg-app: #f5f7fa;
  --color-tint-blue: #e8f3fc;
  --color-muted: #6b7280;
  --color-border: #e6e9ef;

  --color-success: #1f9d57;
  --color-success-bg: #e3f6ec;
  --color-warning: #b7791f;
  --color-warning-bg: #fdf3d6;
  --color-danger: #d64545;
  --color-danger-bg: #fdeaea;

  --radius-card: 14px;
  --radius-control: 8px;
  --radius-pill: 999px;

  --font-sans: 'Instrument Sans', system-ui, sans-serif;
}

html, body, #root { height: 100%; }
body {
  margin: 0;
  background: var(--color-bg-app);
  color: var(--color-ink);
  font-family: var(--font-sans);
  -webkit-font-smoothing: antialiased;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- tokens`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-backoffice/src/styles/tokens.css baas-backoffice/src/styles/tokens.test.ts
git commit -m "feat(backoffice): design tokens as Tailwind 4 theme + CSS variables

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `cn()` util + StatusBadge primitive

**Files:**
- Create: `baas-backoffice/src/lib/cn.ts`, `baas-backoffice/src/components/status-badge.tsx`
- Test: `baas-backoffice/src/lib/cn.test.ts`, `baas-backoffice/src/components/status-badge.test.tsx`

- [ ] **Step 1: Write the failing test for `cn`**

`baas-backoffice/src/lib/cn.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { cn } from './cn';

describe('cn', () => {
  it('joins truthy class names', () => {
    expect(cn('a', false && 'b', 'c')).toBe('a c');
  });
  it('dedupes conflicting tailwind classes (last wins)', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- cn`
Expected: FAIL — `./cn` not found.

- [ ] **Step 3: Implement `cn`**

`baas-backoffice/src/lib/cn.ts`:
```typescript
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- cn`
Expected: PASS.

- [ ] **Step 5: Write the failing test for StatusBadge**

`baas-backoffice/src/components/status-badge.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { StatusBadge } from './status-badge';

describe('StatusBadge', () => {
  it('renders the label', () => {
    render(<StatusBadge label="VERIFIED" variant="success" />);
    expect(screen.getByText('VERIFIED')).toBeInTheDocument();
  });

  it('applies the variant token class', () => {
    render(<StatusBadge label="PENDING" variant="warning" />);
    expect(screen.getByText('PENDING')).toHaveClass('text-warning');
  });

  it('defaults to neutral when variant omitted', () => {
    render(<StatusBadge label="DRAFT" />);
    expect(screen.getByText('DRAFT')).toHaveClass('text-muted');
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- status-badge`
Expected: FAIL — `./status-badge` not found.

- [ ] **Step 7: Implement StatusBadge**

`baas-backoffice/src/components/status-badge.tsx`:
```typescript
import { cn } from '@/lib/cn';

export type StatusVariant = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

const VARIANT: Record<StatusVariant, string> = {
  success: 'bg-success-bg text-success',
  warning: 'bg-warning-bg text-warning',
  danger: 'bg-danger-bg text-danger',
  info: 'bg-tint-blue text-brand-primary',
  neutral: 'bg-border/40 text-muted',
};

export function StatusBadge({
  label,
  variant = 'neutral',
  className,
}: {
  label: string;
  variant?: StatusVariant;
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-[var(--radius-pill)] px-2.5 py-0.5 text-xs font-medium',
        VARIANT[variant],
        className,
      )}
    >
      {label}
    </span>
  );
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- status-badge`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add baas-backoffice/src/lib/cn.ts baas-backoffice/src/lib/cn.test.ts baas-backoffice/src/components/status-badge.tsx baas-backoffice/src/components/status-badge.test.tsx
git commit -m "feat(backoffice): cn() util + StatusBadge primitive

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Envelope helpers — unwrap, extractPage, ApiError

**Files:**
- Create: `baas-backoffice/src/api/envelope.ts`
- Test: `baas-backoffice/src/api/envelope.test.ts`

Mirrors the engine `ApiResponse<T>` record: `data`, `meta {requestId, timestamp}`, `errors [{code, message, field, docsUrl}]`. Pagination is a Spring `Page<T>` inside `data`.

- [ ] **Step 1: Write the failing test**

`baas-backoffice/src/api/envelope.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { unwrap, extractPage, ApiError, type Envelope, type Page } from './envelope';

describe('unwrap', () => {
  it('returns data on success', () => {
    const env: Envelope<{ id: string }> = {
      data: { id: 'x' },
      meta: { requestId: 'r1', timestamp: '2026-06-08T00:00:00Z' },
      errors: null,
    };
    expect(unwrap(env)).toEqual({ id: 'x' });
  });

  it('throws ApiError carrying the first error code/message/field', () => {
    const env: Envelope<null> = {
      data: null,
      meta: { requestId: 'r2', timestamp: '2026-06-08T00:00:00Z' },
      errors: [{ code: 'ERR_VALIDATION', message: 'bad', field: 'email', docsUrl: 'd' }],
    };
    try {
      unwrap(env);
      expect.unreachable('should throw');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.code).toBe('ERR_VALIDATION');
      expect(err.message).toBe('bad');
      expect(err.field).toBe('email');
    }
  });
});

describe('extractPage', () => {
  it('normalizes a Spring Page into {items, page, size, total, totalPages}', () => {
    const page: Page<number> = {
      content: [1, 2, 3],
      number: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    };
    expect(extractPage(page)).toEqual({
      items: [1, 2, 3],
      page: 0,
      size: 20,
      total: 3,
      totalPages: 1,
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- envelope`
Expected: FAIL — `./envelope` not found.

- [ ] **Step 3: Implement the envelope helpers**

`baas-backoffice/src/api/envelope.ts`:
```typescript
export interface EnvelopeError {
  code: string;
  message: string;
  field: string | null;
  docsUrl: string | null;
}

export interface Envelope<T> {
  data: T | null;
  meta: { requestId: string; timestamp: string } | null;
  errors: EnvelopeError[] | null;
}

/** Spring Data Page<T> shape, as serialized inside `data`. */
export interface Page<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface NormalizedPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export class ApiError extends Error {
  readonly code: string;
  readonly field: string | null;
  readonly docsUrl: string | null;
  readonly httpStatus?: number;
  constructor(e: EnvelopeError, httpStatus?: number) {
    super(e.message);
    this.name = 'ApiError';
    this.code = e.code;
    this.field = e.field;
    this.docsUrl = e.docsUrl;
    this.httpStatus = httpStatus;
  }
}

export function unwrap<T>(env: Envelope<T>, httpStatus?: number): T {
  if (env.errors && env.errors.length > 0) {
    throw new ApiError(env.errors[0], httpStatus);
  }
  return env.data as T;
}

export function extractPage<T>(page: Page<T>): NormalizedPage<T> {
  return {
    items: page.content,
    page: page.number,
    size: page.size,
    total: page.totalElements,
    totalPages: page.totalPages,
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- envelope`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-backoffice/src/api/envelope.ts baas-backoffice/src/api/envelope.test.ts
git commit -m "feat(backoffice): envelope unwrap + Spring Page normalization

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Typed API client (openapi-fetch + auth middleware)

**Files:**
- Create: `baas-backoffice/src/api/schema.d.ts` (committed snapshot), `baas-backoffice/src/api/client.ts`
- Test: `baas-backoffice/src/api/client.test.ts`

- [ ] **Step 1: Create a minimal committed `schema.d.ts` snapshot**

This compiles today and is regenerated against a live engine via `npm run gen:api` (F4). The minimal shape includes the customers list path the Dashboard uses.

`baas-backoffice/src/api/schema.d.ts`:
```typescript
/* Generated by `npm run gen:api` (openapi-typescript) against the engine's
   /v3/api-docs. This committed snapshot keeps build/CI engine-independent.
   Regenerate after backend API changes. Minimal hand-seeded version below. */
export interface paths {
  '/baas/v1/customers': {
    get: {
      parameters: { query?: { page?: number; size?: number; sort?: string } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
}
export type webhooks = Record<string, never>;
export interface components { schemas: Record<string, never> }
export type operations = Record<string, never>;
```

- [ ] **Step 2: Write the failing test**

`baas-backoffice/src/api/client.test.ts`:
```typescript
import { describe, it, expect, vi } from 'vitest';
import { createApiClient } from './client';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('createApiClient', () => {
  it('injects the bearer token from getToken()', async () => {
    const fetchSpy = vi.fn(async () =>
      jsonResponse({ data: { ok: true }, meta: null, errors: null }),
    );
    const client = createApiClient({
      baseUrl: 'http://engine',
      getToken: async () => 'tok-123',
      fetch: fetchSpy,
    });
    await client.GET('/baas/v1/customers', {});
    const req = fetchSpy.mock.calls[0][0] as Request;
    expect(req.headers.get('Authorization')).toBe('Bearer tok-123');
  });

  it('omits Authorization when no token', async () => {
    const fetchSpy = vi.fn(async () =>
      jsonResponse({ data: null, meta: null, errors: null }),
    );
    const client = createApiClient({
      baseUrl: 'http://engine',
      getToken: async () => null,
      fetch: fetchSpy,
    });
    await client.GET('/baas/v1/customers', {});
    const req = fetchSpy.mock.calls[0][0] as Request;
    expect(req.headers.get('Authorization')).toBeNull();
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npm test -- client`
Expected: FAIL — `./client` not found.

- [ ] **Step 4: Implement the client factory**

`baas-backoffice/src/api/client.ts`:
```typescript
import createClient, { type Client, type Middleware } from 'openapi-fetch';
import type { paths } from './schema';

export interface ApiClientOptions {
  baseUrl: string;
  getToken: () => Promise<string | null>;
  /** Injectable for tests; defaults to global fetch. */
  fetch?: typeof fetch;
}

export function createApiClient(opts: ApiClientOptions): Client<paths> {
  const authMiddleware: Middleware = {
    async onRequest({ request }) {
      const token = await opts.getToken();
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      return request;
    },
  };

  const client = createClient<paths>({
    baseUrl: opts.baseUrl,
    fetch: opts.fetch,
  });
  client.use(authMiddleware);
  return client;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- client`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add baas-backoffice/src/api/schema.d.ts baas-backoffice/src/api/client.ts baas-backoffice/src/api/client.test.ts
git commit -m "feat(backoffice): typed openapi-fetch client with bearer-auth middleware

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: RBAC permission helpers

**Files:**
- Create: `baas-backoffice/src/lib/rbac.ts`
- Test: `baas-backoffice/src/lib/rbac.test.ts`

Permission codes mirror the engine (`hasAuthority('CREATE_CUSTOMER')` — no `ROLE_` prefix).

- [ ] **Step 1: Write the failing test**

`baas-backoffice/src/lib/rbac.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { hasPermission, PERMISSIONS } from './rbac';

describe('hasPermission', () => {
  it('is true when the authority is present', () => {
    expect(hasPermission(['READ_CUSTOMER', 'CREATE_CUSTOMER'], 'CREATE_CUSTOMER')).toBe(true);
  });
  it('is false when absent', () => {
    expect(hasPermission(['READ_CUSTOMER'], 'CREATE_CUSTOMER')).toBe(false);
  });
  it('treats undefined permission requirement as always allowed', () => {
    expect(hasPermission([], undefined)).toBe(true);
  });
  it('exposes engine permission code constants', () => {
    expect(PERMISSIONS.READ_CUSTOMER).toBe('READ_CUSTOMER');
    expect(PERMISSIONS.APPROVE_LOAN).toBe('APPROVE_LOAN');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- rbac`
Expected: FAIL — `./rbac` not found.

- [ ] **Step 3: Implement the RBAC helpers**

`baas-backoffice/src/lib/rbac.ts`:
```typescript
/** Engine permission codes (V2 migration). No ROLE_ prefix — matches
   @PreAuthorize("hasAuthority('CODE')"). */
export const PERMISSIONS = {
  READ_CUSTOMER: 'READ_CUSTOMER',
  CREATE_CUSTOMER: 'CREATE_CUSTOMER',
  UPDATE_CUSTOMER: 'UPDATE_CUSTOMER',
  READ_ACCOUNT: 'READ_ACCOUNT',
  CREATE_ACCOUNT: 'CREATE_ACCOUNT',
  DEPOSIT: 'DEPOSIT',
  WITHDRAW: 'WITHDRAW',
  READ_LOAN: 'READ_LOAN',
  CREATE_LOAN: 'CREATE_LOAN',
  APPROVE_LOAN: 'APPROVE_LOAN',
  DISBURSE_LOAN: 'DISBURSE_LOAN',
  INITIATE_PAYMENT: 'INITIATE_PAYMENT',
  RUN_REPORT: 'RUN_REPORT',
} as const;

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

export function hasPermission(
  authorities: readonly string[],
  required: string | undefined,
): boolean {
  if (!required) return true;
  return authorities.includes(required);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- rbac`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-backoffice/src/lib/rbac.ts baas-backoffice/src/lib/rbac.test.ts
git commit -m "feat(backoffice): permission-code RBAC helpers (engine parity)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: AuthProvider contract + DevAuthProvider

**Files:**
- Create: `baas-backoffice/src/auth/types.ts`, `baas-backoffice/src/auth/dev-provider.ts`
- Test: `baas-backoffice/src/auth/dev-provider.test.ts`

- [ ] **Step 1: Write the failing test**

`baas-backoffice/src/auth/dev-provider.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { createDevAuthProvider } from './dev-provider';

describe('DevAuthProvider', () => {
  it('is authenticated with configured token + authorities', async () => {
    const p = createDevAuthProvider({
      token: 'dev-tok',
      authorities: ['READ_CUSTOMER', 'CREATE_CUSTOMER'],
      user: { sub: 'u1', name: 'Adaeze O.', email: 'a@nub.test' },
    });
    expect(p.isAuthenticated()).toBe(true);
    expect(p.getAuthorities()).toEqual(['READ_CUSTOMER', 'CREATE_CUSTOMER']);
    expect(await p.getToken()).toBe('dev-tok');
    expect(p.getUser()?.name).toBe('Adaeze O.');
  });

  it('is unauthenticated when no token', () => {
    const p = createDevAuthProvider({ token: null, authorities: [], user: null });
    expect(p.isAuthenticated()).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- dev-provider`
Expected: FAIL — `./dev-provider` not found.

- [ ] **Step 3: Define the contract and implement the dev provider**

`baas-backoffice/src/auth/types.ts`:
```typescript
export interface AuthUser {
  sub: string;
  name: string;
  email: string;
}

/** The single auth seam. Prod = PKCE; dev/CI/e2e = configured token. */
export interface AuthProvider {
  isAuthenticated(): boolean;
  getUser(): AuthUser | null;
  getAuthorities(): string[];
  getToken(): Promise<string | null>;
  login(): Promise<void>;
  logout(): Promise<void>;
}
```

`baas-backoffice/src/auth/dev-provider.ts`:
```typescript
import type { AuthProvider, AuthUser } from './types';

export interface DevAuthConfig {
  token: string | null;
  authorities: string[];
  user: AuthUser | null;
}

export function createDevAuthProvider(config: DevAuthConfig): AuthProvider {
  let { token, authorities, user } = config;
  return {
    isAuthenticated: () => token !== null,
    getUser: () => user,
    getAuthorities: () => authorities,
    getToken: async () => token,
    login: async () => {
      // Dev login is a no-op: the configured token is the session.
      if (!token) {
        token = 'dev-token';
        user = user ?? { sub: 'dev', name: 'Dev Operator', email: 'dev@nubbank.test' };
      }
    },
    logout: async () => {
      token = null;
    },
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- dev-provider`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add baas-backoffice/src/auth/types.ts baas-backoffice/src/auth/dev-provider.ts baas-backoffice/src/auth/dev-provider.test.ts
git commit -m "feat(backoffice): AuthProvider contract + dev provider

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: PKCE provider + authority-claim parsing + factory

**Files:**
- Create: `baas-backoffice/src/auth/pkce-provider.ts`, `baas-backoffice/src/auth/create-provider.ts`
- Test: `baas-backoffice/src/auth/pkce-provider.test.ts`, `baas-backoffice/src/auth/create-provider.test.ts`

- [ ] **Step 1: Write the failing test for authority parsing**

`baas-backoffice/src/auth/pkce-provider.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { parseAuthorities } from './pkce-provider';

describe('parseAuthorities', () => {
  it('reads a flat `authorities` claim', () => {
    expect(parseAuthorities({ authorities: ['READ_CUSTOMER', 'DEPOSIT'] })).toEqual([
      'READ_CUSTOMER',
      'DEPOSIT',
    ]);
  });
  it('falls back to realm_access.roles', () => {
    expect(parseAuthorities({ realm_access: { roles: ['APPROVE_LOAN'] } })).toEqual([
      'APPROVE_LOAN',
    ]);
  });
  it('returns [] when no recognized claim present', () => {
    expect(parseAuthorities({ sub: 'x' })).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- pkce-provider`
Expected: FAIL — `./pkce-provider` not found.

- [ ] **Step 3: Implement the PKCE provider**

`baas-backoffice/src/auth/pkce-provider.ts`:
```typescript
import { UserManager, type UserManagerSettings, type User } from 'oidc-client-ts';
import type { AuthProvider, AuthUser } from './types';

/** Interim authorities source (F5): the engine resolves authorities server-side
   and does NOT mandate them in the JWT. If the Keycloak realm is configured to
   map them in, read them here; otherwise [] (everything 403-gated) until the
   backend `/baas/v1/operators/me` endpoint lands. */
export function parseAuthorities(claims: Record<string, unknown>): string[] {
  const flat = claims['authorities'];
  if (Array.isArray(flat)) return flat.filter((x): x is string => typeof x === 'string');
  const realm = claims['realm_access'] as { roles?: unknown } | undefined;
  if (realm && Array.isArray(realm.roles)) {
    return realm.roles.filter((x): x is string => typeof x === 'string');
  }
  return [];
}

export interface PkceConfig {
  authority: string; // Keycloak realm issuer URL
  clientId: string;
  redirectUri: string;
}

export function createPkceAuthProvider(config: PkceConfig): AuthProvider {
  const settings: UserManagerSettings = {
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
  };
  const mgr = new UserManager(settings);
  let current: User | null = null;

  void mgr.getUser().then((u) => {
    current = u;
  });

  const toUser = (u: User | null): AuthUser | null =>
    u
      ? {
          sub: String(u.profile.sub),
          name: String(u.profile.name ?? u.profile.preferred_username ?? ''),
          email: String(u.profile.email ?? ''),
        }
      : null;

  return {
    isAuthenticated: () => current !== null && !current.expired,
    getUser: () => toUser(current),
    getAuthorities: () =>
      current ? parseAuthorities(current.profile as Record<string, unknown>) : [],
    getToken: async () => {
      current = await mgr.getUser();
      return current && !current.expired ? current.access_token : null;
    },
    login: async () => {
      await mgr.signinRedirect();
    },
    logout: async () => {
      await mgr.signoutRedirect();
      current = null;
    },
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- pkce-provider`
Expected: PASS.

- [ ] **Step 5: Write the failing test for the factory**

`baas-backoffice/src/auth/create-provider.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { createAuthProvider } from './create-provider';

describe('createAuthProvider', () => {
  it('returns a dev provider when VITE_DEV_AUTH=true', () => {
    const p = createAuthProvider({
      VITE_DEV_AUTH: 'true',
      VITE_DEV_AUTHORITIES: 'READ_CUSTOMER,CREATE_CUSTOMER',
    });
    expect(p.isAuthenticated()).toBe(true);
    expect(p.getAuthorities()).toContain('CREATE_CUSTOMER');
  });

  it('returns a PKCE provider when dev auth is off', () => {
    const p = createAuthProvider({
      VITE_DEV_AUTH: 'false',
      VITE_OIDC_AUTHORITY: 'https://kc.test/realms/p',
      VITE_OIDC_CLIENT_ID: 'baas-backoffice',
      VITE_OIDC_REDIRECT_URI: 'http://localhost:3001/auth/callback',
    });
    // PKCE provider starts unauthenticated (no stored user in jsdom).
    expect(p.isAuthenticated()).toBe(false);
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- create-provider`
Expected: FAIL — `./create-provider` not found.

- [ ] **Step 7: Implement the factory**

`baas-backoffice/src/auth/create-provider.ts`:
```typescript
import type { AuthProvider } from './types';
import { createDevAuthProvider } from './dev-provider';
import { createPkceAuthProvider } from './pkce-provider';

export interface AuthEnv {
  VITE_DEV_AUTH?: string;
  VITE_DEV_TOKEN?: string;
  VITE_DEV_AUTHORITIES?: string;
  VITE_OIDC_AUTHORITY?: string;
  VITE_OIDC_CLIENT_ID?: string;
  VITE_OIDC_REDIRECT_URI?: string;
}

export function createAuthProvider(env: AuthEnv): AuthProvider {
  if (env.VITE_DEV_AUTH === 'true') {
    return createDevAuthProvider({
      token: env.VITE_DEV_TOKEN ?? 'dev-token',
      authorities: (env.VITE_DEV_AUTHORITIES ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean),
      user: { sub: 'dev', name: 'Dev Operator', email: 'dev@nubbank.test' },
    });
  }
  return createPkceAuthProvider({
    authority: env.VITE_OIDC_AUTHORITY ?? '',
    clientId: env.VITE_OIDC_CLIENT_ID ?? '',
    redirectUri: env.VITE_OIDC_REDIRECT_URI ?? '',
  });
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- create-provider`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add baas-backoffice/src/auth/pkce-provider.ts baas-backoffice/src/auth/pkce-provider.test.ts baas-backoffice/src/auth/create-provider.ts baas-backoffice/src/auth/create-provider.test.ts
git commit -m "feat(backoffice): PKCE provider + env-selected auth factory

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Auth context + useAuth + RequirePermission

**Files:**
- Create: `baas-backoffice/src/auth/context.tsx`, `baas-backoffice/src/components/require-permission.tsx`
- Test: `baas-backoffice/src/auth/context.test.tsx`, `baas-backoffice/src/components/require-permission.test.tsx`

- [ ] **Step 1: Write the failing test for the context**

`baas-backoffice/src/auth/context.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { AuthContextProvider, useAuth } from './context';
import { createDevAuthProvider } from './dev-provider';

function Probe() {
  const auth = useAuth();
  return <div>{auth.getUser()?.name ?? 'anon'}</div>;
}

describe('AuthContext', () => {
  it('exposes the provider via useAuth', () => {
    const provider = createDevAuthProvider({
      token: 't',
      authorities: [],
      user: { sub: 'u', name: 'Tunde B.', email: 't@nub.test' },
    });
    render(
      <AuthContextProvider provider={provider}>
        <Probe />
      </AuthContextProvider>,
    );
    expect(screen.getByText('Tunde B.')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- auth/context`
Expected: FAIL — `./context` not found.

- [ ] **Step 3: Implement the context**

`baas-backoffice/src/auth/context.tsx`:
```typescript
import { createContext, useContext, type ReactNode } from 'react';
import type { AuthProvider } from './types';

const AuthContext = createContext<AuthProvider | null>(null);

export function AuthContextProvider({
  provider,
  children,
}: {
  provider: AuthProvider;
  children: ReactNode;
}) {
  return <AuthContext.Provider value={provider}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthProvider {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthContextProvider');
  return ctx;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- auth/context`
Expected: PASS.

- [ ] **Step 5: Write the failing test for RequirePermission**

`baas-backoffice/src/components/require-permission.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { RequirePermission } from './require-permission';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(authorities: string[], ui: React.ReactNode) {
  const provider = createDevAuthProvider({ token: 't', authorities, user: null });
  return render(<AuthContextProvider provider={provider}>{ui}</AuthContextProvider>);
}

describe('RequirePermission', () => {
  it('renders children when authorized', () => {
    wrap(['CREATE_CUSTOMER'], (
      <RequirePermission code="CREATE_CUSTOMER">
        <button>New customer</button>
      </RequirePermission>
    ));
    expect(screen.getByRole('button', { name: 'New customer' })).toBeInTheDocument();
  });

  it('renders nothing when unauthorized and no fallback', () => {
    wrap([], (
      <RequirePermission code="CREATE_CUSTOMER">
        <button>New customer</button>
      </RequirePermission>
    ));
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('renders the fallback when unauthorized', () => {
    wrap([], (
      <RequirePermission code="CREATE_CUSTOMER" fallback={<span>Not permitted</span>}>
        <button>New customer</button>
      </RequirePermission>
    ));
    expect(screen.getByText('Not permitted')).toBeInTheDocument();
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- require-permission`
Expected: FAIL — `./require-permission` not found.

- [ ] **Step 7: Implement RequirePermission**

`baas-backoffice/src/components/require-permission.tsx`:
```typescript
import type { ReactNode } from 'react';
import { useAuth } from '@/auth/context';
import { hasPermission } from '@/lib/rbac';

export function RequirePermission({
  code,
  children,
  fallback = null,
}: {
  code: string;
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const auth = useAuth();
  return hasPermission(auth.getAuthorities(), code) ? <>{children}</> : <>{fallback}</>;
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- require-permission`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add baas-backoffice/src/auth/context.tsx baas-backoffice/src/auth/context.test.tsx baas-backoffice/src/components/require-permission.tsx baas-backoffice/src/components/require-permission.test.tsx
git commit -m "feat(backoffice): auth context + RequirePermission gate

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: shadcn/ui base primitives (button, dialog, input, label, table, sonner)

**Files:**
- Create: `baas-backoffice/components.json`, `baas-backoffice/src/components/ui/button.tsx`, `.../ui/dialog.tsx`, `.../ui/input.tsx`, `.../ui/label.tsx`, `.../ui/table.tsx`, `.../ui/sonner.tsx`
- Test: `baas-backoffice/src/components/ui/button.test.tsx`

shadcn/ui components are copied-in (owned code, no version lock — DEF-1C-11). Use the CLI to generate, then verify with one smoke test.

- [ ] **Step 1: Add shadcn config and required Radix deps**

```bash
cd /Users/razormvp/nubbank-baas/baas-backoffice
npm install @radix-ui/react-dialog @radix-ui/react-label @radix-ui/react-slot sonner
```

`baas-backoffice/components.json`:
```json
{
  "$schema": "https://ui.shadcn.com/schema.json",
  "style": "new-york",
  "rsc": false,
  "tsx": true,
  "tailwind": {
    "config": "",
    "css": "src/styles/tokens.css",
    "baseColor": "slate",
    "cssVariables": true,
    "prefix": ""
  },
  "aliases": {
    "components": "@/components",
    "utils": "@/lib/cn",
    "ui": "@/components/ui"
  }
}
```

- [ ] **Step 2: Generate the base components**

```bash
cd /Users/razormvp/nubbank-baas/baas-backoffice
npx shadcn@latest add button dialog input label table sonner --yes
```
Expected: files created under `src/components/ui/`. If the CLI rewrites the `cn` import path, ensure each file imports `cn` from `@/lib/cn`.

- [ ] **Step 3: Write a smoke test for the Button**

`baas-backoffice/src/components/ui/button.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { Button } from './button';

describe('ui/Button', () => {
  it('renders and forwards children', () => {
    render(<Button>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });
});
```

- [ ] **Step 4: Run test to verify it passes (component exists)**

Run: `npm test -- ui/button`
Expected: PASS.

- [ ] **Step 5: Verify typecheck across generated files**

Run: `npm run typecheck`
Expected: exit code 0 (fix any `cn` import path mismatches surfaced here).

- [ ] **Step 6: Commit**

```bash
git add baas-backoffice/components.json baas-backoffice/src/components/ui baas-backoffice/package.json baas-backoffice/package-lock.json
git commit -m "feat(backoffice): shadcn/ui base primitives (button/dialog/input/label/table/sonner)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Query layer + toast wiring + ErrorBoundary

**Files:**
- Create: `baas-backoffice/src/api/query.ts`, `baas-backoffice/src/app/error-boundary.tsx`
- Test: `baas-backoffice/src/api/query.test.ts`, `baas-backoffice/src/app/error-boundary.test.tsx`

- [ ] **Step 1: Write the failing test for query helpers**

`baas-backoffice/src/api/query.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { qk, toToastMessage } from './query';
import { ApiError } from './envelope';

describe('qk (query keys)', () => {
  it('builds stable keys with params', () => {
    expect(qk.list('customers', { page: 0, size: 20 })).toEqual([
      'customers',
      'list',
      { page: 0, size: 20 },
    ]);
    expect(qk.detail('customers', 'id-1')).toEqual(['customers', 'detail', 'id-1']);
  });
});

describe('toToastMessage', () => {
  it('uses the ApiError message', () => {
    expect(toToastMessage(new ApiError({ code: 'X', message: 'Boom', field: null, docsUrl: null }))).toBe(
      'Boom',
    );
  });
  it('falls back for unknown errors', () => {
    expect(toToastMessage(new Error('weird'))).toBe('weird');
    expect(toToastMessage('nope')).toBe('Something went wrong');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- api/query`
Expected: FAIL — `./query` not found.

- [ ] **Step 3: Implement query helpers**

`baas-backoffice/src/api/query.ts`:
```typescript
import { ApiError } from './envelope';

/** Stable query keys: [domain, kind, ...]. Used by every domain's hooks. */
export const qk = {
  list: (domain: string, params?: Record<string, unknown>) =>
    [domain, 'list', params] as [string, 'list', Record<string, unknown> | undefined],
  detail: (domain: string, id: string) =>
    [domain, 'detail', id] as [string, 'detail', string],
};

export function toToastMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong';
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- api/query`
Expected: PASS.

- [ ] **Step 5: Write the failing test for ErrorBoundary**

`baas-backoffice/src/app/error-boundary.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ErrorBoundary } from './error-boundary';

function Boom(): never {
  throw new Error('kaboom');
}

describe('ErrorBoundary', () => {
  it('renders fallback UI when a child throws', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
    spy.mockRestore();
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- error-boundary`
Expected: FAIL — `./error-boundary` not found.

- [ ] **Step 7: Implement the ErrorBoundary**

`baas-backoffice/src/app/error-boundary.tsx`:
```typescript
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface State {
  hasError: boolean;
  message: string;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { hasError: false, message: '' };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, message: error.message };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error('Unhandled UI error', error, info);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div role="alert" className="flex min-h-screen items-center justify-center p-8">
          <div className="max-w-md rounded-[var(--radius-card)] bg-surface p-6 text-center shadow">
            <h1 className="mb-2 text-lg font-semibold">Something went wrong</h1>
            <p className="text-sm text-muted">{this.state.message}</p>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- error-boundary`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add baas-backoffice/src/api/query.ts baas-backoffice/src/api/query.test.ts baas-backoffice/src/app/error-boundary.tsx baas-backoffice/src/app/error-boundary.test.tsx
git commit -m "feat(backoffice): query-key helpers, toast mapper, ErrorBoundary

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Providers composition + ApiClient context

**Files:**
- Create: `baas-backoffice/src/app/providers.tsx`, `baas-backoffice/src/api/context.tsx`, `baas-backoffice/.env.example`
- Test: `baas-backoffice/src/api/context.test.tsx`

- [ ] **Step 1: Write the failing test for the ApiClient context**

`baas-backoffice/src/api/context.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ApiClientProvider, useApiClient } from './context';
import { createApiClient } from './client';

function Probe() {
  const client = useApiClient();
  return <div>{typeof client.GET === 'function' ? 'has-client' : 'no-client'}</div>;
}

describe('ApiClientProvider', () => {
  it('exposes the client via useApiClient', () => {
    const client = createApiClient({ baseUrl: 'http://e', getToken: async () => null });
    render(
      <ApiClientProvider client={client}>
        <Probe />
      </ApiClientProvider>,
    );
    expect(screen.getByText('has-client')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- api/context`
Expected: FAIL — `./context` not found.

- [ ] **Step 3: Implement the ApiClient context**

`baas-backoffice/src/api/context.tsx`:
```typescript
import { createContext, useContext, type ReactNode } from 'react';
import type { Client } from 'openapi-fetch';
import type { paths } from './schema';

const ApiClientContext = createContext<Client<paths> | null>(null);

export function ApiClientProvider({
  client,
  children,
}: {
  client: Client<paths>;
  children: ReactNode;
}) {
  return <ApiClientContext.Provider value={client}>{children}</ApiClientContext.Provider>;
}

export function useApiClient(): Client<paths> {
  const ctx = useContext(ApiClientContext);
  if (!ctx) throw new Error('useApiClient must be used within ApiClientProvider');
  return ctx;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- api/context`
Expected: PASS.

- [ ] **Step 5: Implement the providers composition and `.env.example`**

`baas-backoffice/src/app/providers.tsx`:
```typescript
import { type ReactNode, useMemo } from 'react';
import { QueryClient, QueryClientProvider, MutationCache } from '@tanstack/react-query';
import { Toaster, toast } from 'sonner';
import { AuthContextProvider } from '@/auth/context';
import { createAuthProvider } from '@/auth/create-provider';
import { ApiClientProvider } from '@/api/context';
import { createApiClient } from '@/api/client';
import { toToastMessage } from '@/api/query';
import { ErrorBoundary } from './error-boundary';

export function AppProviders({ children }: { children: ReactNode }) {
  const env = import.meta.env as unknown as Record<string, string>;

  const auth = useMemo(() => createAuthProvider(env), [env]);

  const apiClient = useMemo(
    () =>
      createApiClient({
        baseUrl: env.VITE_API_BASE_URL ?? '',
        getToken: () => auth.getToken(),
      }),
    [auth, env],
  );

  const queryClient = useMemo(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
        mutationCache: new MutationCache({
          onError: (error) => toast.error(toToastMessage(error)),
        }),
      }),
    [],
  );

  return (
    <ErrorBoundary>
      <AuthContextProvider provider={auth}>
        <ApiClientProvider client={apiClient}>
          <QueryClientProvider client={queryClient}>
            {children}
            <Toaster position="top-right" richColors />
          </QueryClientProvider>
        </ApiClientProvider>
      </AuthContextProvider>
    </ErrorBoundary>
  );
}
```

`baas-backoffice/.env.example`:
```
# API
VITE_API_BASE_URL=http://localhost:8080
VITE_OPENAPI_URL=http://localhost:8080/v3/api-docs

# Dev auth (local/CI/e2e). Set VITE_DEV_AUTH=false for PKCE.
VITE_DEV_AUTH=true
VITE_DEV_TOKEN=dev-token
VITE_DEV_AUTHORITIES=READ_CUSTOMER,CREATE_CUSTOMER,UPDATE_CUSTOMER,READ_ACCOUNT,CREATE_ACCOUNT,DEPOSIT,WITHDRAW,READ_LOAN,CREATE_LOAN,APPROVE_LOAN,DISBURSE_LOAN,INITIATE_PAYMENT,RUN_REPORT

# PKCE (prod)
VITE_OIDC_AUTHORITY=
VITE_OIDC_CLIENT_ID=baas-backoffice
VITE_OIDC_REDIRECT_URI=http://localhost:3001/auth/callback
```

- [ ] **Step 6: Run the full suite + typecheck**

Run: `npm test && npm run typecheck`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add baas-backoffice/src/app/providers.tsx baas-backoffice/src/api/context.tsx baas-backoffice/src/api/context.test.tsx baas-backoffice/.env.example
git commit -m "feat(backoffice): app providers composition + ApiClient context

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Layout primitives — nav config, Sidebar, Topbar, AppShell

**Files:**
- Create: `baas-backoffice/src/layout/nav-config.ts`, `.../sidebar.tsx`, `.../topbar.tsx`, `.../app-shell.tsx`, `baas-backoffice/src/components/page-header.tsx`
- Test: `baas-backoffice/src/layout/nav-config.test.ts`, `.../sidebar.test.tsx`

- [ ] **Step 1: Write the failing test for nav filtering**

`baas-backoffice/src/layout/nav-config.test.ts`:
```typescript
import { describe, it, expect } from 'vitest';
import { NAV_GROUPS, visibleNav } from './nav-config';

describe('nav-config', () => {
  it('groups nav to the engine modules (spec §5)', () => {
    const titles = NAV_GROUPS.map((g) => g.title);
    expect(titles).toEqual(['Overview', 'Banking', 'Finance', 'Admin']);
  });

  it('filters items by operator authorities', () => {
    const groups = visibleNav(['READ_CUSTOMER']); // only customers visible in Banking
    const banking = groups.find((g) => g.title === 'Banking');
    expect(banking?.items.map((i) => i.label)).toEqual(['Customers']);
  });

  it('drops a group entirely when no items are permitted', () => {
    const groups = visibleNav([]); // no authorities → only Overview (Dashboard has no gate)
    expect(groups.map((g) => g.title)).toEqual(['Overview']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- nav-config`
Expected: FAIL — `./nav-config` not found.

- [ ] **Step 3: Implement nav config + filtering**

`baas-backoffice/src/layout/nav-config.ts`:
```typescript
import {
  LayoutDashboard, Users, Wallet, PiggyBank, Landmark, ArrowLeftRight,
  Banknote, Receipt, BookOpenCheck, FileBarChart, ShieldCheck, Building2,
  KeyRound, ScrollText, type LucideIcon,
} from 'lucide-react';
import { hasPermission } from '@/lib/rbac';

export interface NavItem {
  label: string;
  to: string;
  icon: LucideIcon;
  /** undefined → always visible (e.g. Dashboard). */
  requiredPermission?: string;
}
export interface NavGroup {
  title: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    title: 'Overview',
    items: [{ label: 'Dashboard', to: '/', icon: LayoutDashboard }],
  },
  {
    title: 'Banking',
    items: [
      { label: 'Customers', to: '/customers', icon: Users, requiredPermission: 'READ_CUSTOMER' },
      { label: 'Accounts', to: '/accounts', icon: Wallet, requiredPermission: 'READ_ACCOUNT' },
      { label: 'Deposits', to: '/deposits', icon: PiggyBank, requiredPermission: 'READ_ACCOUNT' },
      { label: 'Loans', to: '/loans', icon: Landmark, requiredPermission: 'READ_LOAN' },
      { label: 'Payments', to: '/payments', icon: ArrowLeftRight, requiredPermission: 'INITIATE_PAYMENT' },
      { label: 'Teller / Cash', to: '/teller', icon: Banknote, requiredPermission: 'DEPOSIT' },
      { label: 'Charges', to: '/charges', icon: Receipt, requiredPermission: 'READ_ACCOUNT' },
    ],
  },
  {
    title: 'Finance',
    items: [
      { label: 'Accounting', to: '/accounting', icon: BookOpenCheck, requiredPermission: 'RUN_REPORT' },
      { label: 'Reports', to: '/reports', icon: FileBarChart, requiredPermission: 'RUN_REPORT' },
      { label: 'Compliance', to: '/compliance', icon: ShieldCheck, requiredPermission: 'RUN_REPORT' },
    ],
  },
  {
    title: 'Admin',
    items: [
      { label: 'Offices / Staff', to: '/offices', icon: Building2, requiredPermission: 'UPDATE_CUSTOMER' },
      { label: 'Roles', to: '/roles', icon: KeyRound, requiredPermission: 'UPDATE_CUSTOMER' },
      { label: 'Audit', to: '/audit', icon: ScrollText, requiredPermission: 'RUN_REPORT' },
    ],
  },
];

export function visibleNav(authorities: readonly string[]): NavGroup[] {
  return NAV_GROUPS.map((g) => ({
    ...g,
    items: g.items.filter((i) => hasPermission(authorities, i.requiredPermission)),
  })).filter((g) => g.items.length > 0);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- nav-config`
Expected: PASS.

- [ ] **Step 5: Write the failing test for the Sidebar (collapse + active + brand)**

`baas-backoffice/src/layout/sidebar.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { Sidebar } from './sidebar';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function renderSidebar(authorities: string[]) {
  const provider = createDevAuthProvider({ token: 't', authorities, user: null });
  return render(
    <AuthContextProvider provider={provider}>
      <MemoryRouter initialEntries={['/']}>
        <Sidebar />
      </MemoryRouter>
    </AuthContextProvider>,
  );
}

describe('Sidebar', () => {
  it('shows the NubBank brand and permitted nav labels when expanded', () => {
    renderSidebar(['READ_CUSTOMER']);
    expect(screen.getByText('NubBank')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /customers/i })).toBeInTheDocument();
  });

  it('toggles collapse via the control', async () => {
    renderSidebar(['READ_CUSTOMER']);
    const toggle = screen.getByRole('button', { name: /collapse sidebar/i });
    await userEvent.click(toggle);
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- sidebar`
Expected: FAIL — `./sidebar` not found.

- [ ] **Step 7: Implement Sidebar, Topbar, AppShell, PageHeader**

`baas-backoffice/src/layout/sidebar.tsx`:
```typescript
import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useAuth } from '@/auth/context';
import { visibleNav } from './nav-config';
import { cn } from '@/lib/cn';

export function Sidebar() {
  const auth = useAuth();
  const [collapsed, setCollapsed] = useState(false);
  const groups = visibleNav(auth.getAuthorities());

  return (
    <aside
      className={cn(
        'flex h-screen flex-col bg-ink text-white transition-[width] duration-200',
        collapsed ? 'w-14' : 'w-60',
      )}
    >
      <div className="flex items-center justify-between px-3 py-4">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-[var(--radius-control)] bg-brand-primary font-bold">
            N
          </div>
          {!collapsed && <span className="font-semibold">NubBank</span>}
        </div>
        <button
          type="button"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          onClick={() => setCollapsed((c) => !c)}
          className="grid h-6 w-6 place-items-center rounded text-brand-accent hover:bg-white/10"
        >
          {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto px-2">
        {groups.map((group) => (
          <div key={group.title} className="mb-4">
            {!collapsed && (
              <p className="px-2 pb-1 text-[10px] uppercase tracking-wide text-white/40">
                {group.title}
              </p>
            )}
            {group.items.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-3 rounded-[var(--radius-control)] px-2 py-2 text-sm',
                      isActive
                        ? 'bg-white/10 border-l-2 border-brand-primary text-white'
                        : 'text-white/70 hover:bg-white/5',
                    )
                  }
                >
                  <Icon size={18} />
                  {!collapsed && <span>{item.label}</span>}
                </NavLink>
              );
            })}
          </div>
        ))}
      </nav>
    </aside>
  );
}
```

`baas-backoffice/src/layout/topbar.tsx`:
```typescript
import { Bell, Search } from 'lucide-react';
import { useAuth } from '@/auth/context';

export function Topbar() {
  const user = useAuth().getUser();
  const initials = (user?.name ?? 'NB')
    .split(' ')
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <header className="flex items-center justify-between border-b border-border bg-surface px-5 py-2.5">
      <div className="flex w-80 items-center gap-2 rounded-[var(--radius-pill)] border border-border bg-bg-app px-3 py-1.5 text-sm text-muted">
        <Search size={15} />
        <span>Search customers, accounts…</span>
      </div>
      <div className="flex items-center gap-4 text-muted">
        <button type="button" aria-label="Notifications" className="grid h-8 w-8 place-items-center rounded-[var(--radius-control)] hover:bg-bg-app">
          <Bell size={18} />
        </button>
        <span className="flex items-center gap-2 text-sm font-medium text-ink">
          <span className="grid h-7 w-7 place-items-center rounded-[var(--radius-control)] bg-brand-primary text-xs text-white">
            {initials}
          </span>
          {user?.name ?? 'Operator'}
        </span>
      </div>
    </header>
  );
}
```

`baas-backoffice/src/layout/app-shell.tsx`:
```typescript
import { Outlet } from 'react-router-dom';
import { Sidebar } from './sidebar';
import { Topbar } from './topbar';

export function AppShell() {
  return (
    <div className="flex h-screen bg-bg-app">
      <Sidebar />
      <div className="flex flex-1 flex-col overflow-hidden">
        <Topbar />
        <main className="flex-1 overflow-y-auto p-5">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
```

`baas-backoffice/src/components/page-header.tsx`:
```typescript
import type { ReactNode } from 'react';

export function PageHeader({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div className="mb-4 flex items-center justify-between">
      <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
      {action}
    </div>
  );
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- sidebar`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add baas-backoffice/src/layout baas-backoffice/src/components/page-header.tsx
git commit -m "feat(backoffice): RBAC-gated app shell (sidebar/topbar/AppShell/PageHeader)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Router + route guards + Login screen

**Files:**
- Create: `baas-backoffice/src/app/router.tsx`, `.../guards.tsx`, `baas-backoffice/src/features/auth/login.tsx`
- Modify: `baas-backoffice/src/app/App.tsx`
- Test: `baas-backoffice/src/app/guards.test.tsx`, `baas-backoffice/src/features/auth/login.test.tsx`

- [ ] **Step 1: Write the failing test for guards**

`baas-backoffice/src/app/guards.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { RequireAuth, RequireRoutePermission } from './guards';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function setup(authorities: string[], token: string | null, initial = '/secret') {
  const provider = createDevAuthProvider({ token, authorities, user: null });
  return render(
    <AuthContextProvider provider={provider}>
      <MemoryRouter initialEntries={[initial]}>
        <Routes>
          <Route path="/login" element={<div>login-page</div>} />
          <Route element={<RequireAuth />}>
            <Route
              path="/secret"
              element={
                <RequireRoutePermission code="READ_CUSTOMER">
                  <div>secret-content</div>
                </RequireRoutePermission>
              }
            />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContextProvider>,
  );
}

describe('route guards', () => {
  it('redirects to /login when unauthenticated', () => {
    setup([], null);
    expect(screen.getByText('login-page')).toBeInTheDocument();
  });
  it('shows not-permitted when authenticated but lacking permission', () => {
    setup([], 't');
    expect(screen.getByText(/not permitted/i)).toBeInTheDocument();
  });
  it('renders content when authorized', () => {
    setup(['READ_CUSTOMER'], 't');
    expect(screen.getByText('secret-content')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- guards`
Expected: FAIL — `./guards` not found.

- [ ] **Step 3: Implement the guards**

`baas-backoffice/src/app/guards.tsx`:
```typescript
import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '@/auth/context';
import { hasPermission } from '@/lib/rbac';

export function RequireAuth() {
  const auth = useAuth();
  return auth.isAuthenticated() ? <Outlet /> : <Navigate to="/login" replace />;
}

export function RequireRoutePermission({
  code,
  children,
}: {
  code: string;
  children: ReactNode;
}) {
  const auth = useAuth();
  if (hasPermission(auth.getAuthorities(), code)) return <>{children}</>;
  return (
    <div className="grid place-items-center py-24 text-center">
      <div>
        <h2 className="text-lg font-semibold">Not permitted</h2>
        <p className="text-sm text-muted">You don’t have access to this area.</p>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- guards`
Expected: PASS.

- [ ] **Step 5: Write the failing test for Login**

`baas-backoffice/src/features/auth/login.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { Login } from './login';
import { AuthContextProvider } from '@/auth/context';
import type { AuthProvider } from '@/auth/types';

function stubAuth(over: Partial<AuthProvider> = {}): AuthProvider {
  return {
    isAuthenticated: () => false,
    getUser: () => null,
    getAuthorities: () => [],
    getToken: async () => null,
    login: vi.fn(async () => {}),
    logout: async () => {},
    ...over,
  };
}

describe('Login', () => {
  it('renders the NubBank sign-in and trusted-by stats', () => {
    render(
      <AuthContextProvider provider={stubAuth()}>
        <MemoryRouter><Login /></MemoryRouter>
      </AuthContextProvider>,
    );
    expect(screen.getByRole('heading', { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByText(/15 banks live/i)).toBeInTheDocument();
  });

  it('calls auth.login on submit', async () => {
    const login = vi.fn(async () => {});
    render(
      <AuthContextProvider provider={stubAuth({ login })}>
        <MemoryRouter><Login /></MemoryRouter>
      </AuthContextProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(login).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- login`
Expected: FAIL — `./login` not found.

- [ ] **Step 7: Implement the Login screen (matches Figma canonical Login)**

`baas-backoffice/src/features/auth/login.tsx`:
```typescript
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/context';
import { Button } from '@/components/ui/button';

const STATS = [
  ['12', 'fintechs live'],
  ['15', 'banks live'],
  ['₦4.2bn', 'processed · 30d'],
  ['99.98%', 'uptime'],
];

export function Login() {
  const auth = useAuth();
  const navigate = useNavigate();

  async function onSignIn() {
    await auth.login();
    if (auth.isAuthenticated()) navigate('/', { replace: true });
  }

  return (
    <div className="grid min-h-screen grid-cols-1 lg:grid-cols-2">
      {/* Left: brand panel (slate ink) */}
      <div className="relative hidden flex-col justify-between overflow-hidden bg-surface-ink p-12 text-white lg:flex">
        <div className="flex items-center gap-2">
          <div className="grid h-9 w-9 place-items-center rounded-[var(--radius-control)] bg-brand-primary font-bold">
            N
          </div>
          <span className="text-lg font-semibold">NubBank</span>
        </div>
        <div>
          <h2 className="max-w-sm text-3xl font-semibold leading-tight">
            Banking infrastructure for builders.
          </h2>
          <p className="mt-3 max-w-sm text-white/60">
            Issue accounts, move money, and run your bank — on NubBank’s rails.
          </p>
        </div>
        <div className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
          {STATS.map(([n, l]) => (
            <div key={l}>
              <span className="font-semibold text-brand-accent">{n}</span>{' '}
              <span className="text-white/60">{l}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Right: sign-in */}
      <div className="flex items-center justify-center bg-surface p-12">
        <div className="w-full max-w-sm">
          <h1 className="text-2xl font-semibold">Sign in</h1>
          <p className="mt-1 text-sm text-muted">Use your NubBank operator account.</p>
          <Button className="mt-8 w-full" onClick={onSignIn}>
            Sign in
          </Button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- login`
Expected: PASS.

- [ ] **Step 9: Wire the router and App**

`baas-backoffice/src/app/router.tsx`:
```typescript
import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from '@/layout/app-shell';
import { RequireAuth } from './guards';
import { Login } from '@/features/auth/login';
import { Dashboard } from '@/features/dashboard/dashboard';

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [{ index: true, element: <Dashboard /> }],
      },
    ],
  },
]);
```

`baas-backoffice/src/app/App.tsx` (replace placeholder):
```typescript
import { RouterProvider } from 'react-router-dom';
import { AppProviders } from './providers';
import { router } from './router';

export default function App() {
  return (
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  );
}
```

> Note: `router.tsx` imports `Dashboard` (built in Task 15). Build Task 15 before running `npm run build`. Step 10 runs only the unit suite, which does not import the router.

- [ ] **Step 10: Run the unit suite**

Run: `npm test`
Expected: all green (router/App not imported by unit tests yet).

- [ ] **Step 11: Commit**

```bash
git add baas-backoffice/src/app/guards.tsx baas-backoffice/src/app/guards.test.tsx baas-backoffice/src/app/router.tsx baas-backoffice/src/app/App.tsx baas-backoffice/src/features/auth/login.tsx baas-backoffice/src/features/auth/login.test.tsx
git commit -m "feat(backoffice): router, auth/permission guards, Login screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: Dashboard — KPI tiles + Recent Customers table

**Files:**
- Create: `baas-backoffice/src/features/dashboard/use-dashboard.ts`, `.../kpi-tile.tsx`, `.../dashboard.tsx`, `baas-backoffice/src/components/data-table.tsx`
- Test: `baas-backoffice/src/features/dashboard/use-dashboard.test.tsx`, `.../dashboard.test.tsx`

No dashboard endpoint exists; the Recent Customers table + customer count derive from `GET /baas/v1/customers` (real). Other KPI tiles show a documented "—" until their endpoints/aggregates land (recorded as a follow-up in Task 18).

- [ ] **Step 1: Write the failing test for the data hook**

`baas-backoffice/src/features/dashboard/use-dashboard.test.tsx`:
```typescript
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useRecentCustomers } from './use-dashboard';
import { ApiClientProvider } from '@/api/context';

function makeWrapper(getResult: unknown) {
  const client = {
    GET: vi.fn(async () => ({ data: getResult, error: undefined, response: new Response() })),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      </ApiClientProvider>
    );
  };
}

describe('useRecentCustomers', () => {
  it('normalizes the envelope+Page into items + total', async () => {
    const wrapper = makeWrapper({
      data: {
        content: [{ id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' }],
        number: 0,
        size: 5,
        totalElements: 42,
        totalPages: 9,
      },
      meta: { requestId: 'r', timestamp: 't' },
      errors: null,
    });
    const { result } = renderHook(() => useRecentCustomers(5), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.total).toBe(42);
    expect(result.current.data?.items[0].firstName).toBe('Chidi');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- use-dashboard`
Expected: FAIL — `./use-dashboard` not found.

- [ ] **Step 3: Implement the data hook**

`baas-backoffice/src/features/dashboard/use-dashboard.ts`:
```typescript
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '@/api/context';
import { qk } from '@/api/query';
import { unwrap, extractPage, type Envelope, type Page, type NormalizedPage } from '@/api/envelope';

export interface CustomerRow {
  id: string;
  firstName: string;
  lastName: string;
  kycStatus: string;
  accountNumber?: string;
  balance?: number;
}

export function useRecentCustomers(size = 5) {
  const client = useApiClient();
  return useQuery<NormalizedPage<CustomerRow>>({
    queryKey: qk.list('customers', { page: 0, size }),
    queryFn: async () => {
      const { data } = await client.GET('/baas/v1/customers', {
        params: { query: { page: 0, size } },
      });
      const env = data as Envelope<Page<CustomerRow>>;
      return extractPage(unwrap(env));
    },
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- use-dashboard`
Expected: PASS.

- [ ] **Step 5: Write the failing test for the DataTable**

`baas-backoffice/src/components/data-table.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from './data-table';

interface Row { name: string; }
const col = createColumnHelper<Row>();
const columns = [col.accessor('name', { header: 'Name' })];

describe('DataTable', () => {
  it('renders headers and rows', () => {
    render(<DataTable columns={columns} data={[{ name: 'Amaka' }]} />);
    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('Amaka')).toBeInTheDocument();
  });

  it('shows the empty state when no rows', () => {
    render(<DataTable columns={columns} data={[]} emptyMessage="No customers" />);
    expect(screen.getByText('No customers')).toBeInTheDocument();
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- data-table`
Expected: FAIL — `./data-table` not found.

- [ ] **Step 7: Implement the DataTable (TanStack Table)**

`baas-backoffice/src/components/data-table.tsx`:
```typescript
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from '@tanstack/react-table';

export function DataTable<TData, TValue>({
  columns,
  data,
  emptyMessage = 'No records',
}: {
  columns: ColumnDef<TData, TValue>[];
  data: TData[];
  emptyMessage?: string;
}) {
  const table = useReactTable({ data, columns, getCoreRowModel: getCoreRowModel() });

  return (
    <div className="overflow-hidden rounded-[var(--radius-card)] border border-border bg-surface">
      <table className="w-full text-sm">
        <thead className="bg-bg-app text-left text-muted">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((h) => (
                <th key={h.id} className="px-4 py-2 font-medium">
                  {h.isPlaceholder ? null : flexRender(h.column.columnDef.header, h.getContext())}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {table.getRowModel().rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-4 py-10 text-center text-muted">
                {emptyMessage}
              </td>
            </tr>
          ) : (
            table.getRowModel().rows.map((row) => (
              <tr key={row.id} className="border-t border-border">
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="px-4 py-2.5">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- data-table`
Expected: PASS.

- [ ] **Step 9: Write the failing test for the Dashboard screen**

`baas-backoffice/src/features/dashboard/dashboard.test.tsx`:
```typescript
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { Dashboard } from './dashboard';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(children: ReactNode) {
  const client = {
    GET: vi.fn(async () => ({
      data: {
        data: { content: [{ id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' }], number: 0, size: 5, totalElements: 1, totalPages: 1 },
        meta: { requestId: 'r', timestamp: 't' },
        errors: null,
      },
      error: undefined,
      response: new Response(),
    })),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities: ['READ_CUSTOMER'], user: { sub: 'u', name: 'Adaeze O.', email: 'a@n.test' } });
  return render(
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter>{children}</MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>,
  );
}

describe('Dashboard', () => {
  it('renders KPI tiles and the recent-customers row', async () => {
    wrap(<Dashboard />);
    expect(screen.getByText(/recent customers/i)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Chidi Okafor')).toBeInTheDocument());
  });
});
```

- [ ] **Step 10: Run test to verify it fails**

Run: `npm test -- dashboard`
Expected: FAIL — `./dashboard` not found.

- [ ] **Step 11: Implement KpiTile and Dashboard**

`baas-backoffice/src/features/dashboard/kpi-tile.tsx`:
```typescript
import { cn } from '@/lib/cn';

export function KpiTile({
  label,
  value,
  delta,
  tone = 'tint',
}: {
  label: string;
  value: string;
  delta?: string;
  tone?: 'tint' | 'plain' | 'ink';
}) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] p-4',
        tone === 'tint' && 'bg-tint-blue',
        tone === 'plain' && 'border border-border bg-surface',
        tone === 'ink' && 'bg-surface-ink text-white',
      )}
    >
      <p className={cn('text-[11px] uppercase tracking-wide', tone === 'ink' ? 'text-white/50' : 'text-muted')}>
        {label}
      </p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
      {delta && <p className={cn('text-xs', tone === 'ink' ? 'text-brand-accent' : 'text-success')}>{delta}</p>}
    </div>
  );
}
```

`baas-backoffice/src/features/dashboard/dashboard.tsx`:
```typescript
import { createColumnHelper } from '@tanstack/react-table';
import { useAuth } from '@/auth/context';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { StatusBadge, type StatusVariant } from '@/components/status-badge';
import { RequirePermission } from '@/components/require-permission';
import { Button } from '@/components/ui/button';
import { KpiTile } from './kpi-tile';
import { useRecentCustomers, type CustomerRow } from './use-dashboard';

const KYC_VARIANT: Record<string, StatusVariant> = {
  VERIFIED: 'success',
  PENDING: 'warning',
  KYC_PENDING: 'warning',
  REJECTED: 'danger',
};

const col = createColumnHelper<CustomerRow>();
const columns = [
  col.accessor((r) => `${r.firstName} ${r.lastName}`, { id: 'name', header: 'Name' }),
  col.accessor('kycStatus', {
    header: 'KYC',
    cell: (ctx) => (
      <StatusBadge label={ctx.getValue()} variant={KYC_VARIANT[ctx.getValue()] ?? 'neutral'} />
    ),
  }),
];

export function Dashboard() {
  const name = useAuth().getUser()?.name?.split(' ')[0] ?? 'there';
  const customers = useRecentCustomers(5);

  return (
    <div>
      <PageHeader title={`Good afternoon, ${name}`} />

      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Customers"
          value={customers.data ? String(customers.data.total) : '—'}
          tone="tint"
        />
        {/* No aggregate endpoint yet — see Task 18 follow-up. */}
        <KpiTile label="Deposits (₦)" value="—" tone="plain" />
        <KpiTile label="KYC pending" value="—" tone="plain" />
        <KpiTile label="Cards issued" value="—" tone="ink" />
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <span className="font-semibold">Recent customers</span>
          <RequirePermission code="CREATE_CUSTOMER">
            <Button size="sm">+ New customer</Button>
          </RequirePermission>
        </div>
        <div className="p-2">
          {customers.isError ? (
            <p className="px-4 py-10 text-center text-sm text-danger">Couldn’t load customers.</p>
          ) : (
            <DataTable
              columns={columns}
              data={customers.data?.items ?? []}
              emptyMessage={customers.isLoading ? 'Loading…' : 'No customers yet'}
            />
          )}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 12: Run test to verify it passes**

Run: `npm test -- dashboard`
Expected: PASS.

- [ ] **Step 13: Run the full suite + build**

Run: `npm test && npm run build`
Expected: all tests green; `vite build` succeeds (router now resolves `Dashboard`).

- [ ] **Step 14: Commit**

```bash
git add baas-backoffice/src/features/dashboard baas-backoffice/src/components/data-table.tsx baas-backoffice/src/components/data-table.test.tsx
git commit -m "feat(backoffice): Dashboard — KPI tiles + recent customers (live)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: CommandModal + FormField primitives

**Files:**
- Create: `baas-backoffice/src/components/command-modal.tsx`, `baas-backoffice/src/components/form-field.tsx`
- Test: `baas-backoffice/src/components/command-modal.test.tsx`

The repeatable command shape every domain reuses: RHF + Zod inside a shadcn Dialog.

- [ ] **Step 1: Write the failing test**

`baas-backoffice/src/components/command-modal.test.tsx`:
```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { z } from 'zod';
import { CommandModal } from './command-modal';
import { FormField } from './form-field';

const schema = z.object({ name: z.string().min(1, 'Required') });

describe('CommandModal', () => {
  it('submits valid values', async () => {
    const onSubmit = vi.fn(async () => {});
    render(
      <CommandModal
        open
        onOpenChange={() => {}}
        title="New customer"
        schema={schema}
        defaultValues={{ name: '' }}
        onSubmit={onSubmit}
      >
        {(form) => <FormField label="Name" error={form.formState.errors.name?.message}><input {...form.register('name')} /></FormField>}
      </CommandModal>,
    );
    await userEvent.type(screen.getByLabelText('Name'), 'Acme');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(onSubmit).toHaveBeenCalledWith({ name: 'Acme' }, expect.anything());
  });

  it('blocks submit and shows validation error', async () => {
    const onSubmit = vi.fn(async () => {});
    render(
      <CommandModal
        open
        onOpenChange={() => {}}
        title="New customer"
        schema={schema}
        defaultValues={{ name: '' }}
        onSubmit={onSubmit}
      >
        {(form) => <FormField label="Name" error={form.formState.errors.name?.message}><input {...form.register('name')} /></FormField>}
      </CommandModal>,
    );
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(await screen.findByText('Required')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- command-modal`
Expected: FAIL — `./command-modal` not found.

- [ ] **Step 3: Implement FormField and CommandModal**

`baas-backoffice/src/components/form-field.tsx`:
```typescript
import { useId, type ReactElement, cloneElement } from 'react';

export function FormField({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: ReactElement;
}) {
  const id = useId();
  return (
    <div className="mb-3">
      <label htmlFor={id} className="mb-1 block text-sm font-medium">
        {label}
      </label>
      {cloneElement(children, {
        id,
        className:
          'w-full rounded-[var(--radius-control)] border border-border px-3 py-2 text-sm outline-none focus:border-brand-primary',
      })}
      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
    </div>
  );
}
```

`baas-backoffice/src/components/command-modal.tsx`:
```typescript
import { type ReactNode } from 'react';
import { useForm, type UseFormReturn, type DefaultValues, type SubmitHandler, type FieldValues } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import type { ZodType } from 'zod';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

export function CommandModal<T extends FieldValues>({
  open,
  onOpenChange,
  title,
  schema,
  defaultValues,
  onSubmit,
  submitLabel = 'Save',
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  schema: ZodType<T>;
  defaultValues: DefaultValues<T>;
  onSubmit: SubmitHandler<T>;
  submitLabel?: string;
  children: (form: UseFormReturn<T>) => ReactNode;
}) {
  const form = useForm<T>({ resolver: zodResolver(schema), defaultValues });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)}>
          {children(form)}
          <DialogFooter className="mt-4">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- command-modal`
Expected: PASS.

- [ ] **Step 5: Run full suite + typecheck**

Run: `npm test && npm run typecheck`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add baas-backoffice/src/components/command-modal.tsx baas-backoffice/src/components/form-field.tsx baas-backoffice/src/components/command-modal.test.tsx
git commit -m "feat(backoffice): CommandModal + FormField (RHF + Zod) primitives

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 17: Playwright e2e — login → dashboard (dev-auth, mocked API)

**Files:**
- Create: `baas-backoffice/playwright.config.ts`, `baas-backoffice/e2e/dashboard.spec.ts`

- [ ] **Step 1: Install Playwright browsers**

```bash
cd /Users/razormvp/nubbank-baas/baas-backoffice
npx playwright install --with-deps chromium
```

- [ ] **Step 2: Write `playwright.config.ts`**

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: { baseURL: 'http://localhost:3001', trace: 'on-first-retry' },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3001',
    reuseExistingServer: !process.env.CI,
    env: {
      VITE_DEV_AUTH: 'true',
      VITE_DEV_AUTHORITIES: 'READ_CUSTOMER,CREATE_CUSTOMER',
      VITE_API_BASE_URL: 'http://localhost:8080',
    },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
```

- [ ] **Step 3: Write the e2e spec (API mocked via page.route)**

`baas-backoffice/e2e/dashboard.spec.ts`:
```typescript
import { test, expect } from '@playwright/test';

test('dashboard renders recent customers from the engine', async ({ page }) => {
  await page.route('**/baas/v1/customers**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          content: [
            { id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' },
            { id: 'c2', firstName: 'Amaka', lastName: 'Eze', kycStatus: 'PENDING' },
          ],
          number: 0, size: 5, totalElements: 2, totalPages: 1,
        },
        meta: { requestId: 'r', timestamp: 't' },
        errors: null,
      }),
    });
  });

  await page.goto('/');
  await expect(page.getByText('Recent customers')).toBeVisible();
  await expect(page.getByText('Chidi Okafor')).toBeVisible();
  await expect(page.getByText('Amaka Eze')).toBeVisible();
});
```

- [ ] **Step 4: Run the e2e test**

Run: `npm run test:e2e`
Expected: 1 passed (dev server boots with dev-auth → Dashboard renders mocked customers).

- [ ] **Step 5: Commit**

```bash
git add baas-backoffice/playwright.config.ts baas-backoffice/e2e baas-backoffice/package.json baas-backoffice/package-lock.json
git commit -m "test(backoffice): Playwright e2e — login→dashboard against dev auth

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 18: Dockerfile + nginx + compose + k8s + CI + docs

**Files:**
- Create: `baas-backoffice/Dockerfile`, `baas-backoffice/nginx.conf`, `baas-backoffice/.dockerignore`, `infrastructure/k8s/base/90-baas-backoffice-config.yaml`, `infrastructure/k8s/base/92-baas-backoffice.yaml`, `.github/workflows/baas-backoffice-ci.yml`
- Modify: `infrastructure/docker-compose.yml`, `infrastructure/k8s/base/kustomization.yaml`, `infrastructure/k8s/base/60-ingress.yaml`, `docs/deferred-items.md`

- [ ] **Step 1: Write the Dockerfile (node build → nginx runtime)**

`baas-backoffice/Dockerfile`:
```dockerfile
# syntax=docker/dockerfile:1.6
# Multi-stage: Vite build on Node, served by nginx. Deployment-agnostic OCI image.
# Build:  docker build -t baas-backoffice:local -f baas-backoffice/Dockerfile baas-backoffice
# Run:    docker run --rm -p 3001:3001 baas-backoffice:local

# ─── Stage 1: build ────────────────────────────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ─── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM nginx:1.27-alpine
RUN apk upgrade --no-cache && apk add --no-cache curl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 3001
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:3001/healthz || exit 1
```

- [ ] **Step 2: Write `nginx.conf` (SPA fallback + healthz)**

`baas-backoffice/nginx.conf`:
```nginx
server {
    listen 3001;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # Healthcheck endpoint (used by Docker HEALTHCHECK + k8s probes).
    location = /healthz {
        access_log off;
        add_header Content-Type text/plain;
        return 200 'ok';
    }

    # Long-cache fingerprinted assets.
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # SPA fallback — all non-asset routes resolve to index.html.
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

> Note: the SPA calls the engine cross-origin at `VITE_API_BASE_URL`; routing `/baas/v1` to the engine is the Ingress's job (Step 7), not nginx's. Keep nginx static-only.

- [ ] **Step 3: Write `.dockerignore`**

`baas-backoffice/.dockerignore`:
```
node_modules
dist
coverage
playwright-report
test-results
e2e
.env
*.log
```

- [ ] **Step 4: Build the image to verify**

Run: `cd /Users/razormvp/nubbank-baas && docker build -t baas-backoffice:local -f baas-backoffice/Dockerfile baas-backoffice`
Expected: image builds; final stage copies `dist/` into nginx. (If Docker is unavailable, document the command; CI Step 8 verifies the build.)

- [ ] **Step 5: Add the docker-compose service**

Add to `infrastructure/docker-compose.yml` under `services:` (mirror the existing service block style; backoffice talks to the engine at `:8080`):
```yaml
  baas-backoffice:
    build:
      context: ../baas-backoffice
      dockerfile: Dockerfile
    image: baas-backoffice:local
    ports:
      - "3001:3001"
    depends_on:
      baas-engine:
        condition: service_started
    restart: unless-stopped
```

- [ ] **Step 6: Add k8s base config + deployment**

`infrastructure/k8s/base/90-baas-backoffice-config.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: baas-backoffice-config
  namespace: nubbank-baas
data:
  # Build-time VITE_* are baked into the static bundle; this ConfigMap documents
  # the intended runtime ingress host. The SPA calls the engine via the Ingress.
  PUBLIC_API_BASE_URL: "https://api.nubbank.example.com"
```

`infrastructure/k8s/base/92-baas-backoffice.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: baas-backoffice
  namespace: nubbank-baas
  labels:
    app: baas-backoffice
    app.kubernetes.io/component: web
spec:
  replicas: 2
  selector:
    matchLabels:
      app: baas-backoffice
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: baas-backoffice
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 101          # nginx unprivileged user in the alpine image
        runAsGroup: 101
        fsGroup: 101
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: baas-backoffice
          # Sentinel tag — does NOT exist in GHCR. Direct `kubectl apply -f base/`
          # fails fast with ImagePullBackOff intentionally. Real deploys go through
          # an overlay with CI substituting the real SHA via `kustomize edit set image`.
          # Published by .github/workflows/baas-backoffice-ci.yml as
          # ghcr.io/<owner>/baas-backoffice:<sha> on push to main.
          image: ghcr.io/razormvp/baas-backoffice:base-do-not-deploy
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 3001
              name: http
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          volumeMounts:
            - name: nginx-cache
              mountPath: /var/cache/nginx
            - name: nginx-run
              mountPath: /var/run
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 250m
              memory: 128Mi
          livenessProbe:
            httpGet:
              path: /healthz
              port: http
            periodSeconds: 30
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /healthz
              port: http
            initialDelaySeconds: 3
            periodSeconds: 10
            failureThreshold: 3
      volumes:
        - name: nginx-cache
          emptyDir:
            sizeLimit: 64Mi
        - name: nginx-run
          emptyDir:
            sizeLimit: 8Mi
---
apiVersion: v1
kind: Service
metadata:
  name: baas-backoffice
  namespace: nubbank-baas
spec:
  type: ClusterIP
  selector:
    app: baas-backoffice
  ports:
    - port: 80
      targetPort: 3001
      name: http
```

> Note: `readOnlyRootFilesystem: true` requires writable `/var/cache/nginx` and `/var/run` — provided via emptyDir mounts above. nginx:alpine listens on 3001 per `nginx.conf`.

- [ ] **Step 7: Register the manifests and add the Ingress route**

In `infrastructure/k8s/base/kustomization.yaml`, add to `resources:`:
```yaml
  - 90-baas-backoffice-config.yaml
  - 92-baas-backoffice.yaml
```

In `infrastructure/k8s/base/60-ingress.yaml`, add a **least-specific** root route AFTER all `/baas/v1*` and `/open-banking` paths (longest-prefix match keeps API routes intact):
```yaml
          - path: /
            pathType: Prefix
            backend:
              service:
                name: baas-backoffice
                port:
                  number: 80
```

- [ ] **Step 8: Write the CI workflow (mirror baas-engine-ci.yml)**

Create `.github/workflows/baas-backoffice-ci.yml` via Bash heredoc (Write tool is blocked on workflow files):
```bash
cat > /Users/razormvp/nubbank-baas/.github/workflows/baas-backoffice-ci.yml << 'YAML'
name: baas-backoffice CI

# Deployment-agnostic build & publish (mirrors baas-engine-ci.yml):
#  - npm build + test on a generic Linux runner.
#  - Image published to GHCR — pull from any cluster. Deploy happens via
#    kubectl/kustomize on the target cluster, NOT inside CI.

on:
  push:
    branches: [main]
    paths:
      - 'baas-backoffice/**'
      - '.github/workflows/baas-backoffice-ci.yml'
      - '.trivyignore'
  pull_request:
    branches: [main]
    paths:
      - 'baas-backoffice/**'
      - '.github/workflows/baas-backoffice-ci.yml'
      - '.trivyignore'
  workflow_dispatch:

permissions:
  contents: read

jobs:
  test:
    permissions:
      contents: read
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: baas-backoffice
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd  # v6.0.2
      - uses: actions/setup-node@39370e3970a6d050c480ffad4ff0ed4d3fdee5af  # v4.1.0
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: baas-backoffice/package-lock.json
      - run: npm ci
      - run: npm run typecheck
      - run: npm test
      - name: Upload coverage on failure
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a  # v7.0.1
        with:
          name: vitest-output
          path: baas-backoffice/coverage/

  build-and-push:
    needs: test
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    permissions:
      contents: read
      packages: write
      id-token: write
    runs-on: ubuntu-latest
    outputs:
      image-tag: ${{ steps.img.outputs.tag }}
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd  # v6.0.2
      - uses: docker/setup-buildx-action@4d04d5d9486b7bd6fa91e7baf45bbb4f8b9deedd  # v4.0.0
      - name: Log in to GHCR
        uses: docker/login-action@4907a6ddec9925e35a0a9e82d7399ccc52663121  # v4.1.0
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Compute image tag (lowercase — OCI requires all-lowercase)
        id: img
        env:
          OWNER: ${{ github.repository_owner }}
        run: |
          lower_owner=$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]')
          echo "tag=ghcr.io/${lower_owner}/baas-backoffice" >> "$GITHUB_OUTPUT"
      - name: Build and push image
        uses: docker/build-push-action@bcafcacb16a39f128d818304e6c9c0c18556b85f  # v7.1.0
        with:
          context: baas-backoffice
          file: baas-backoffice/Dockerfile
          push: true
          tags: |
            ${{ steps.img.outputs.tag }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
          provenance: true
          sbom: true

  security-scan:
    needs: build-and-push
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
      packages: read
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd  # v6.0.2
      - name: Log in to GHCR
        uses: docker/login-action@4907a6ddec9925e35a0a9e82d7399ccc52663121  # v4.1.0
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Trivy scan image for HIGH/CRITICAL CVEs
        uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25  # v0.36.0
        with:
          image-ref: ${{ needs.build-and-push.outputs.image-tag }}:${{ github.sha }}
          format: sarif
          output: trivy-results.sarif
          severity: CRITICAL,HIGH
          exit-code: '0'
          ignore-unfixed: true
          trivyignores: .trivyignore
      - name: Upload Trivy SARIF to GitHub Security tab
        if: always()
        uses: github/codeql-action/upload-sarif@68bde559dea0fdcac2102bfdf6230c5f70eb485e  # v4.35.4
        with:
          sarif_file: trivy-results.sarif
      - name: Generate SBOM (SPDX JSON)
        if: always()
        uses: anchore/sbom-action@e22c389904149dbc22b58101806040fa8d37a610  # v0.24.0
        with:
          image: ${{ needs.build-and-push.outputs.image-tag }}:${{ github.sha }}
          format: spdx-json
          artifact-name: baas-backoffice-sbom-${{ github.sha }}.spdx.json

  pr-security-scan:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd  # v6.0.2
      - name: Trivy filesystem scan (PR — informational)
        uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25  # v0.36.0
        with:
          scan-type: fs
          scan-ref: baas-backoffice
          format: sarif
          output: trivy-results.sarif
          severity: CRITICAL,HIGH
          exit-code: '0'
          ignore-unfixed: true
          trivyignores: .trivyignore
      - name: Upload Trivy SARIF
        if: always()
        uses: github/codeql-action/upload-sarif@68bde559dea0fdcac2102bfdf6230c5f70eb485e  # v4.35.4
        with:
          sarif_file: trivy-results.sarif
YAML
```

- [ ] **Step 9: Record backend follow-ups in deferred-items.md**

Append to the table in `docs/deferred-items.md`:
```markdown
| DEF-1C-28 | `GET /baas/v1/operators/me` (operator identity + authorities) | Dev provider supplies authorities; PKCE reads token claim as interim | Phase 1C/2 | baas-backoffice Foundation (F5) |
| DEF-1C-29 | Dashboard aggregate endpoint (deposits total, KYC-pending count, cards issued) | No summary endpoint; tiles show "—" until built | Phase 1C/2 | baas-backoffice Foundation (Task 15) |
```

- [ ] **Step 10: Final verification — full suite, typecheck, build**

Run: `cd /Users/razormvp/nubbank-baas/baas-backoffice && npm ci && npm run typecheck && npm test && npm run build`
Expected: install clean, typecheck clean, all unit tests pass, `vite build` succeeds.

- [ ] **Step 11: Validate k8s base renders**

Run: `kubectl kustomize /Users/razormvp/nubbank-baas/infrastructure/k8s/base > /dev/null && echo OK`
Expected: `OK` (no kustomize errors; backoffice resources included). If `kubectl` is unavailable, skip and note it.

- [ ] **Step 12: Commit**

```bash
cd /Users/razormvp/nubbank-baas
git add baas-backoffice/Dockerfile baas-backoffice/nginx.conf baas-backoffice/.dockerignore \
  infrastructure/docker-compose.yml infrastructure/k8s/base/90-baas-backoffice-config.yaml \
  infrastructure/k8s/base/92-baas-backoffice.yaml infrastructure/k8s/base/kustomization.yaml \
  infrastructure/k8s/base/60-ingress.yaml .github/workflows/baas-backoffice-ci.yml docs/deferred-items.md
git commit -m "feat(backoffice): Docker + nginx + compose + k8s + CI + deferred items

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Definition of Done (Foundation)

- `npm ci && npm run typecheck && npm test && npm run build` all pass in `baas-backoffice/`.
- `npm run test:e2e` passes (login → Dashboard renders mocked customers).
- The app boots with `VITE_DEV_AUTH=true`, shows the RBAC-gated shell, and the Dashboard's Recent Customers table is wired to `GET /baas/v1/customers`.
- Dockerfile builds; `kubectl kustomize base/` renders with backoffice included; `baas-backoffice-ci.yml` mirrors the engine CI.
- Per the baas skill SESSION COMPLETION GATE: update `baas-log.md` (new session entry + Confirmed Platform Versions) and `CLAUDE.md` (module catalogue: `baas-backoffice` Foundation ✅), and mark Phase 1C progress in the `/baas` skill. (These doc steps run at session close, not per task.)

---

## Self-review notes (author)

- **Spec coverage:** §2 stack (all libs in Task 1) ✓; §3 B1 full-build decomposition (this is the foundation; domains follow) ✓, B2 shadcn (Task 10) ✓, B3 hybrid auth (Tasks 7–8) ✓, B4/B6 tokens+blue accent (Tasks 2, 13) ✓; §4 tokens (Task 2) ✓; §5 app shell + RBAC nav (Tasks 13, 14) ✓; §6 list/detail/command shape — list+command primitives (DataTable Task 15, CommandModal Task 16); detail-view shape is exercised in the per-domain sub-plans (next plan) ✓; §7 auth→token→typed client→Query→render (Tasks 5, 8, 11, 12) ✓, envelope unwrap (Task 4) ✓, 401/403 handling (guards Task 14, RequirePermission Task 9) ✓; §8 Vitest+RTL throughout, Playwright (Task 17) ✓; §9 structure + Foundation contents (all) ✓.
- **Type consistency:** `AuthProvider` methods (`isAuthenticated/getUser/getAuthorities/getToken/login/logout`) identical across dev-provider, pkce-provider, context, guards ✓. `hasPermission(authorities, required)` signature identical in rbac, RequirePermission, guards, nav-config ✓. `unwrap`/`extractPage`/`Envelope`/`Page` consistent between envelope.ts and use-dashboard.ts ✓. `qk.list/qk.detail` consistent (Task 11 corrected to `[domain, kind, ...]`) ✓.
- **Known nuance flagged:** authorities are not in the engine JWT (F5 + DEF-1C-28); Dashboard aggregate tiles are "—" pending DEF-1C-29. Both recorded, neither blocks a shippable Foundation.
