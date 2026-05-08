# Phase 1F-E — Infrastructure Hardening

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all 6 critical and 14 important findings from the 1E retrospective review on the Phase 1E infrastructure that was direct-committed to `main` with no PR. After Plan E merges, the infrastructure passes Pod Security Admission `restricted`, has no `:latest` references, has working healthchecks in Compose, and has container scanning + SBOM + provenance attestation in CI. Plan E is independent of Plans 0/A/B and can run in parallel with any of them.

**Architecture:** Two layers of fixes: (1) Dockerfile-level (healthcheck binary, jar glob, Maven cache split, base-image digest pinning, `.dockerignore`, JVM hardening flags into ENTRYPOINT); (2) Kubernetes-level (replace `:latest` with SHA-templated images, declare missing `baas-ncube-config` ConfigMap, harden Postgres StatefulSet with SecurityContext + resources + livenessProbe + PDB, add PodSecurityContext + NetworkPolicy + startupProbe + PDB to both Deployments, split `baas-ncube-secrets` from `baas-engine-secrets`, switch healthchecks to `/actuator/health/readiness`, add `imagePullSecrets` docs); plus (3) CI hardening (Trivy, SBOM, provenance, action SHA pinning, scoped `packages: write`).

**Tech Stack:** Docker (BuildKit), Kubernetes vanilla manifests, GitHub Actions, Trivy, anchore/sbom-action, Renovate (config only — no runtime change).

**Branch:** `feature/phase1f-e-infra`, branched from `main` at `17c2e3e`.

**Findings addressed:** 1E C1, C2, C3, C4, C5, C6, I1, I2, I3, I4, I5, I6, I7, I8, I9, I10, I12, I13, I14, m1–m12, m7. (6 critical + 13 important + 13 minor.)

---

## File Structure

### New files

| File | Purpose |
|------|---------|
| `infrastructure/k8s/base/kustomization.yaml` | Kustomize base — lists all eight base manifests as `resources:` |
| `infrastructure/k8s/overlays/dev/kustomization.yaml` | dev overlay — image SHAs only, no NetworkPolicy |
| `infrastructure/k8s/overlays/staging/kustomization.yaml` | staging overlay — image SHAs + opt-in NetworkPolicy component |
| `infrastructure/k8s/overlays/prod/kustomization.yaml` | prod overlay — image SHAs + opt-in NetworkPolicy component |
| `infrastructure/k8s/components/network-policy/kustomization.yaml` | Kustomize Component (`kind: Component`) wrapping the NetworkPolicy manifest |
| `infrastructure/k8s/components/network-policy/15-network-policy.yaml` | default-deny + targeted allows (engine→postgres, engine→ncube, ingress→engine, ncube→external) |
| `infrastructure/k8s/base/16-baas-ncube-config.yaml` | the ConfigMap currently referenced but undefined (declares `NIBSS_NPS_BASE_URL`) |
| `infrastructure/k8s/base/17-baas-ncube-secrets.example.yaml` | separate Secret template for ncube-only credentials |
| `infrastructure/k8s/base/70-pod-disruption-budgets.yaml` | PDBs for engine, ncube, postgres |
| `baas-engine/.dockerignore` | exclude target/, .git, .idea, *.iml, .env*, .vscode, docs/, *.md from build context |
| `baas-ncube/.dockerignore` | same |
| `.github/CODEOWNERS` | requires review on `infrastructure/**` and `.github/workflows/**` |
| `infrastructure/.env.example` | re-declared if missing — placeholders use `<CHANGE_ME>` style |

### Modified files

| File | Change |
|------|--------|
| `baas-engine/Dockerfile` | install `curl` in runtime stage; switch healthcheck to `/actuator/health/readiness`; rename jar to deterministic `app.jar` via `finalName`; split Maven `dependency:go-offline`; bake JVM flags into ENTRYPOINT; pin base image to digest; bump `--start-period` to `120s` |
| `baas-ncube/Dockerfile` | same set of changes (mirrored) |
| `infrastructure/docker-compose.yml` | bind Postgres to `127.0.0.1:5432` only; replace healthcheck `wget` with `curl`; stricter `CHANGE_ME` placeholder |
| `infrastructure/k8s/base/30-postgres.yaml` | add full `securityContext` (pod + container), `resources`, `livenessProbe`, fix `pg_isready -U postgres` literal |
| `infrastructure/k8s/base/40-baas-engine.yaml` | pin image to sentinel `:base-do-not-deploy` (overlays substitute SHA via Kustomize `images:`); add full `securityContext`; add `startupProbe`; switch probes to `/actuator/health/readiness`; lower HPA target to 60 |
| `infrastructure/k8s/base/50-baas-ncube.yaml` | same set; switch `envFrom` to `baas-ncube-secrets` (not engine's); reference `baas-ncube-config` ConfigMap declared in 16- file; lower HPA target |
| `infrastructure/k8s/base/60-ingress.yaml` | TODO marker for real host (Task 21) |
| `infrastructure/k8s/README.md` | document Kustomize layout, GHCR `imagePullSecrets`, SHA tagging pattern, cert-manager hook, CODEOWNERS expectation |
| `.github/workflows/baas-engine-ci.yml` | scope `permissions: { packages: write }` to `build-and-push` job only; add Trivy + SBOM + provenance after image push; pin all `actions/*@vN` to commit SHAs |
| `.github/workflows/baas-ncube-ci.yml` | same |
| `baas-engine/pom.xml` | add `<finalName>app</finalName>` so jar produces deterministic name |
| `baas-ncube/pom.xml` | same |

---

## Task 1 — Fix Dockerfile healthcheck binary (`wget` → `curl`)

`eclipse-temurin:21-jre-alpine` does not bundle `wget`. Both Dockerfiles' `HEALTHCHECK` exits 127 every cycle, so Compose's `service_healthy` dependencies never fire.

**Files:**
- Modify: `baas-engine/Dockerfile`
- Modify: `baas-ncube/Dockerfile`
- Modify: `infrastructure/docker-compose.yml`

- [ ] **Step 1: Verify wget is missing**

```bash
docker run --rm eclipse-temurin:21-jre-alpine which wget
```
Expected: empty stdout, exit 1 — confirming the bug.

- [ ] **Step 2: Update `baas-engine/Dockerfile` runtime stage**

Replace the `RUN addgroup ... USER app` block with:

```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
```

Replace the `HEALTHCHECK` directive with:

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1
```

(`/readiness` instead of `/health` — readiness aggregates DB connectivity, which is what we want; and `--start-period=120s` for slow Flyway migrations on first boot.)

- [ ] **Step 3: Same changes in `baas-ncube/Dockerfile`** — replace `8080` with `8082` in the curl URL.

- [ ] **Step 4: Update `infrastructure/docker-compose.yml`** healthchecks:

```yaml
healthcheck:
  test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8080/actuator/health/readiness"]
  interval: 30s
  timeout: 5s
  start_period: 120s
  retries: 3
```

For ncube, change port to 8082.

- [ ] **Step 5: Build + verify**

```bash
docker build -t baas-engine:test -f baas-engine/Dockerfile baas-engine
# Confirm no errors. Check the image:
docker run --rm baas-engine:test which curl
# Expected: /usr/bin/curl
```

- [ ] **Step 6: Commit**

```bash
git add baas-engine/Dockerfile baas-ncube/Dockerfile infrastructure/docker-compose.yml
git commit -m "fix(infra): install curl in runtime images; healthchecks use /readiness (1E C1)"
```

---

## Task 2 — Make Dockerfile jar copy deterministic

`COPY --from=build /workspace/target/*.jar app.jar` silently picks one of multiple jars if `maven-source-plugin` or `maven-javadoc-plugin` ever lands. Also, Spring Boot leaves `.jar.original` alongside the executable jar.

**Files:**
- Modify: `baas-engine/pom.xml`
- Modify: `baas-engine/Dockerfile`
- Modify: `baas-ncube/pom.xml`
- Modify: `baas-ncube/Dockerfile`

- [ ] **Step 1: Add `<finalName>app</finalName>` to both `pom.xml`** under `<build>`:

```xml
<build>
  <finalName>app</finalName>
  ...existing plugins...
</build>
```

This produces `target/app.jar` and `target/app.jar.original`.

- [ ] **Step 2: Update both Dockerfiles** — change the `COPY` line:

```dockerfile
COPY --from=build /workspace/target/app.jar app.jar
```

(Specific name; no glob; future `-sources.jar` doesn't break.)

- [ ] **Step 3: Verify build still works**

```bash
docker build -t baas-engine:test -f baas-engine/Dockerfile baas-engine
docker build -t baas-ncube:test -f baas-ncube/Dockerfile baas-ncube
```

- [ ] **Step 4: Commit**

```bash
git add baas-engine/pom.xml baas-engine/Dockerfile baas-ncube/pom.xml baas-ncube/Dockerfile
git commit -m "fix(infra): deterministic jar name (finalName=app) — no more wildcard COPY (1E C5)"
```

---

## Task 3 — Split Maven `dependency:go-offline` for layer cache

Source changes shouldn't invalidate the dependency layer.

**Files:**
- Modify: `baas-engine/Dockerfile`
- Modify: `baas-ncube/Dockerfile`

- [ ] **Step 1: Update both Dockerfiles** — replace the build stage:

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
```

- [ ] **Step 2: Confirm cache hit on second build**

```bash
docker build -t baas-engine:t1 -f baas-engine/Dockerfile baas-engine
# Touch a Java file
touch baas-engine/src/main/java/com/nubbank/baas/engine/BaasEngineApplication.java
docker build -t baas-engine:t2 -f baas-engine/Dockerfile baas-engine
# Confirm second build's "RUN ... dependency:go-offline" shows "CACHED"
```

- [ ] **Step 3: Commit**

```bash
git add baas-engine/Dockerfile baas-ncube/Dockerfile
git commit -m "perf(infra): split mvn dependency:go-offline so source changes don't invalidate dep cache (1E C6)"
```

---

## Task 4 — Move JVM hardening flags into ENTRYPOINT

`ENV JAVA_TOOL_OPTIONS=...` is overridable by anyone setting that env var on the running container. Bake the security baseline into the immutable ENTRYPOINT.

**Files:**
- Modify: `baas-engine/Dockerfile`
- Modify: `baas-ncube/Dockerfile`

- [ ] **Step 1: Replace the last two lines of each Dockerfile** with:

```dockerfile
# Security baseline baked into ENTRYPOINT — not overridable via JAVA_TOOL_OPTIONS env var.
# To add runtime options at deploy time, append to the deployment's args field.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

(Drop the `ENV JAVA_TOOL_OPTIONS=...` line entirely.)

- [ ] **Step 2: Build + run a quick smoke**

```bash
docker run --rm baas-engine:test java -version 2>&1 | head -2
# Should NOT show JAVA_TOOL_OPTIONS being picked up.
```

- [ ] **Step 3: Commit**

```bash
git add baas-engine/Dockerfile baas-ncube/Dockerfile
git commit -m "fix(infra): JVM hardening flags in ENTRYPOINT, not overridable env var (1E I9)"
```

---

## Task 5 — Pin base image to digest

Floating tag `:21-jre-alpine` can shift overnight; reproducible builds require a digest.

**Files:**
- Modify: `baas-engine/Dockerfile`
- Modify: `baas-ncube/Dockerfile`

- [ ] **Step 1: Resolve the current digest**

```bash
docker pull eclipse-temurin:21-jre-alpine
docker inspect eclipse-temurin:21-jre-alpine \
    --format='{{index .RepoDigests 0}}'
# Output, e.g.:
# eclipse-temurin@sha256:abc123def456...
```

Capture that digest.

- [ ] **Step 2: Update both Dockerfiles**

Replace `FROM eclipse-temurin:21-jre-alpine` with:

```dockerfile
FROM eclipse-temurin:21-jre-alpine@sha256:<DIGEST_FROM_STEP_1>
```

Same for the build stage's `maven:3.9-eclipse-temurin-21-alpine` if that has a digest worth pinning (recommended).

- [ ] **Step 3: Add a comment note** at the top of each Dockerfile:

```dockerfile
# Base image digest pinned for reproducibility. Renovate updates this weekly.
```

- [ ] **Step 4: Commit**

```bash
git add baas-engine/Dockerfile baas-ncube/Dockerfile
git commit -m "fix(infra): pin base image digests for reproducibility (1E I12)"
```

---

## Task 6 — Add `.dockerignore` files

Avoid leaking `target/`, `.git`, `.env`, and IDE caches into the build context.

**Files:**
- Create: `baas-engine/.dockerignore`
- Create: `baas-ncube/.dockerignore`

- [ ] **Step 1: Write both `.dockerignore` files** with the same content:

```
target/
.git/
.idea/
*.iml
.env
.env.*
.vscode/
.mvn/wrapper/maven-wrapper.jar
docs/
*.md
.DS_Store
```

- [ ] **Step 2: Verify build context shrinks**

```bash
# Before/after comparison:
du -sh baas-engine          # without .dockerignore
docker build --no-cache -f baas-engine/Dockerfile baas-engine 2>&1 | head -3
# Compare "Sending build context" line — should be much smaller after .dockerignore lands.
```

- [ ] **Step 3: Commit**

```bash
git add baas-engine/.dockerignore baas-ncube/.dockerignore
git commit -m "fix(infra): add .dockerignore files to both modules (1E I14)"
```

---

## Task 7 — Restructure k8s manifests as a Kustomize tree (deterministic image SHAs)

`:latest` + `IfNotPresent` causes non-deterministic deploys and breaks rollback. The fix is per-environment image pinning with rollback-safe tags. Adopting Kustomize now (rather than `IMAGE_TAG_PLACEHOLDER`+`sed`) gives us native image substitution, environment overlays (dev/staging/prod), and a clean opt-in path for NetworkPolicy (Task 11) — no third-party tooling required (`kustomize` is built into `kubectl >= 1.14`).

The base manifests carry a sentinel tag (`:base-do-not-deploy`) so a careless `kubectl apply -f base/` cannot accidentally pull a non-existent image. Overlays substitute the real SHA via Kustomize's `images:` block at deploy time.

**Files:**
- Create: `infrastructure/k8s/base/kustomization.yaml`
- Create: `infrastructure/k8s/overlays/dev/kustomization.yaml`
- Create: `infrastructure/k8s/overlays/staging/kustomization.yaml`
- Create: `infrastructure/k8s/overlays/prod/kustomization.yaml`
- Move (`git mv`): all eight existing files in `infrastructure/k8s/*.yaml` → `infrastructure/k8s/base/`
- Modify: `infrastructure/k8s/base/40-baas-engine.yaml` — image to `ghcr.io/razormvp/baas-engine:base-do-not-deploy`
- Modify: `infrastructure/k8s/base/50-baas-ncube.yaml` — image to `ghcr.io/razormvp/baas-ncube:base-do-not-deploy`
- Modify: `infrastructure/k8s/README.md`

> **Subsequent tasks reference `infrastructure/k8s/base/...`** — Tasks 8–14 modify base manifests; Task 11 adds NetworkPolicy as a Kustomize Component (opt-in per overlay). Update file paths in those tasks if you re-order the implementation.

- [ ] **Step 1: Move existing manifests into `base/`**

```bash
cd infrastructure/k8s
mkdir -p base overlays/dev overlays/staging overlays/prod
git mv 00-namespace.yaml 10-secrets.example.yaml 20-configmap.yaml \
       30-postgres.yaml 40-baas-engine.yaml 50-baas-ncube.yaml \
       60-ingress.yaml base/
# README stays at the top so the directory has a discoverable entry point.
```

- [ ] **Step 2: Pin both Deployments to the sentinel base tag**

In `base/40-baas-engine.yaml`:

```yaml
spec:
  template:
    spec:
      containers:
        - name: baas-engine
          image: ghcr.io/razormvp/baas-engine:base-do-not-deploy
          imagePullPolicy: IfNotPresent  # safe — overlays substitute SHA tags before apply
```

Same pattern in `base/50-baas-ncube.yaml` for `baas-ncube`.

The `:base-do-not-deploy` tag does not exist in GHCR, so `kubectl apply -f base/` fails with `ImagePullBackOff` — the intended safety guard. Real deploys always go through an overlay.

- [ ] **Step 3: Write the base `kustomization.yaml`**

Create `infrastructure/k8s/base/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: nubbank-baas

resources:
  - 00-namespace.yaml
  - 10-secrets.example.yaml
  - 20-configmap.yaml
  - 30-postgres.yaml
  - 40-baas-engine.yaml
  - 50-baas-ncube.yaml
  - 60-ingress.yaml
```

- [ ] **Step 4: Write the dev overlay (image SHA must be supplied at deploy time)**

Create `infrastructure/k8s/overlays/dev/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../../base

# Overlays MUST set image SHAs explicitly. CI rewrites these via `kustomize edit set image`
# before `kubectl apply -k`. Never commit a value other than `base-do-not-deploy` here —
# CI rewrites it transiently and reverts before the workflow exits, OR uses a separate
# build artifact directory so the source tree is untouched.
images:
  - name: ghcr.io/razormvp/baas-engine
    newTag: base-do-not-deploy
  - name: ghcr.io/razormvp/baas-ncube
    newTag: base-do-not-deploy
```

- [ ] **Step 5: Write the staging and prod overlays (same shape, different overlay paths)**

Create `infrastructure/k8s/overlays/staging/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../../base

images:
  - name: ghcr.io/razormvp/baas-engine
    newTag: base-do-not-deploy
  - name: ghcr.io/razormvp/baas-ncube
    newTag: base-do-not-deploy
```

Create `infrastructure/k8s/overlays/prod/kustomization.yaml` — identical content. (NetworkPolicy components are added in Task 11; they will only be wired into staging and prod overlays at that point.)

- [ ] **Step 6: Update `infrastructure/k8s/README.md` — replace the old "Deploy" section**

```markdown
## Layout

    infrastructure/k8s/
    ├── base/                — deployable manifests, no image SHAs
    ├── overlays/
    │   ├── dev/             — dev-only image SHAs, no NetworkPolicy
    │   ├── staging/         — staging SHAs + NetworkPolicy component (Task 11)
    │   └── prod/            — prod SHAs + NetworkPolicy component (Task 11)
    └── components/          — opt-in cross-cutting (added in Task 11)

The base manifests pin images to `:base-do-not-deploy`, a sentinel tag that does not exist
in GHCR. Direct `kubectl apply -f base/` therefore fails fast — an intentional safety guard.
Real deploys always go through an overlay.

## Deploying a specific commit

Substitute the image SHA into the chosen overlay, render, and apply:

    SHA=$(git rev-parse HEAD)
    cd infrastructure/k8s/overlays/prod
    kustomize edit set image ghcr.io/razormvp/baas-engine=ghcr.io/razormvp/baas-engine:sha-${SHA}
    kustomize edit set image ghcr.io/razormvp/baas-ncube=ghcr.io/razormvp/baas-ncube:sha-${SHA}
    kubectl apply -k .

CI must commit `kustomization.yaml` back to `base-do-not-deploy` after deploy
(or work in a tempdir copy). Never check in a real SHA into the overlay file in git.

`kubectl rollout undo deployment/baas-engine -n nubbank-baas` revives the prior commit's image
because each Deployment revision retains its full pod spec including the SHA tag.
```

- [ ] **Step 7: Verify Kustomize renders cleanly**

```bash
cd infrastructure/k8s
kubectl kustomize base/             > /tmp/base-render.yaml
kubectl kustomize overlays/dev/     > /tmp/dev-render.yaml
kubectl kustomize overlays/staging/ > /tmp/staging-render.yaml
kubectl kustomize overlays/prod/    > /tmp/prod-render.yaml
# Expected: each render contains 7 documents (Namespace, Secret, ConfigMap, StatefulSet,
# 2 Deployments, Ingress) and the image tag is base-do-not-deploy.
grep -c '^---' /tmp/base-render.yaml   # Expected: 6 (separators between 7 docs)
grep 'image: ghcr.io' /tmp/dev-render.yaml
# Expected (per Deployment):
#   image: ghcr.io/razormvp/baas-engine:base-do-not-deploy
#   image: ghcr.io/razormvp/baas-ncube:base-do-not-deploy
yamllint base/*.yaml overlays/**/kustomization.yaml || true
```

- [ ] **Step 8: Commit**

```bash
git add infrastructure/k8s/
git commit -m "fix(k8s): restructure manifests as Kustomize tree with sentinel base tag (1E C2)"
```

---

## Task 8 — Declare the missing `baas-ncube-config` ConfigMap

`50-baas-ncube.yaml` references `baas-ncube-config` but it's never declared. With `optional: true`, the pod starts in stub mode silently.

**Files:**
- Create: `infrastructure/k8s/base/16-baas-ncube-config.yaml`
- Modify: `infrastructure/k8s/base/50-baas-ncube.yaml`
- Modify: `infrastructure/k8s/base/kustomization.yaml` (register the new resource)

- [ ] **Step 1: Create `infrastructure/k8s/base/16-baas-ncube-config.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: baas-ncube-config
  namespace: nubbank-baas
data:
  NIBSS_NPS_BASE_URL: "https://nps-sandbox.nibss-plc.com.ng"   # replace per environment
  NPS_LIVE: "false"   # set to "true" only when wired to live NIBSS
  NPS_MEMBER_ID: "999058"
  NPS_INSTITUTION_NAME: "NubBank BaaS"
```

- [ ] **Step 2: Update `50-baas-ncube.yaml`** — drop `optional: true` so missing ConfigMap fails the pod fast (intentional fail-loud):

```yaml
envFrom:
  - configMapRef:
      name: baas-ncube-config
      # optional: true   ← REMOVED — pod must fail to start if config is missing
```

- [ ] **Step 3: Register the new resource in `base/kustomization.yaml`**

Add `16-baas-ncube-config.yaml` to the `resources:` list (preserve numeric ordering):

```yaml
resources:
  - 00-namespace.yaml
  - 10-secrets.example.yaml
  - 16-baas-ncube-config.yaml   # ← new
  - 20-configmap.yaml
  - 30-postgres.yaml
  - 40-baas-engine.yaml
  - 50-baas-ncube.yaml
  - 60-ingress.yaml
```

- [ ] **Step 4: Verify**

```bash
kubectl --dry-run=client apply -f infrastructure/k8s/base/16-baas-ncube-config.yaml
kubectl --dry-run=client apply -f infrastructure/k8s/base/50-baas-ncube.yaml
kubectl kustomize infrastructure/k8s/base/ | grep -A1 'name: baas-ncube-config'
# Expected: ConfigMap appears in render output
```

- [ ] **Step 5: Commit**

```bash
git add infrastructure/k8s/base/16-baas-ncube-config.yaml \
         infrastructure/k8s/base/50-baas-ncube.yaml \
         infrastructure/k8s/base/kustomization.yaml
git commit -m "fix(k8s): declare missing baas-ncube-config ConfigMap; drop optional fallback (1E C3)"
```

---

## Task 9 — Harden Postgres StatefulSet

Add SecurityContext, resource requests/limits, livenessProbe, and fix the brittle `$(POSTGRES_USER)` substitution.

**Files:**
- Modify: `infrastructure/k8s/base/30-postgres.yaml`

- [ ] **Step 1: Update the StatefulSet** — add to `spec.template.spec`:

```yaml
spec:
  template:
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 70           # postgres
        runAsGroup: 70
        fsGroup: 70
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: postgres
          # ...existing image/env/ports...
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: false   # postgres needs write to data dir
            capabilities:
              drop: [ALL]
          resources:
            requests:
              cpu: "250m"
              memory: "512Mi"
            limits:
              memory: "2Gi"   # leave CPU unlimited
          livenessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U postgres"]
            initialDelaySeconds: 30
            periodSeconds: 30
            timeoutSeconds: 5
            failureThreshold: 6
          readinessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U postgres"]
            initialDelaySeconds: 10
            periodSeconds: 10
            timeoutSeconds: 5
```

(Replace any existing `$(POSTGRES_USER)` substitution with literal `postgres` since the env var is set in the same container.)

- [ ] **Step 2: Verify**

```bash
kubectl --dry-run=client apply -f infrastructure/k8s/base/30-postgres.yaml
```

- [ ] **Step 3: Commit**

```bash
git add infrastructure/k8s/base/30-postgres.yaml
git commit -m "fix(k8s): postgres SecurityContext + resources + livenessProbe (1E C4)"
```

---

## Task 10 — Add PodSecurityContext + container SecurityContext to both Deployments

Pod Security Admission `restricted` rejects pods without `runAsNonRoot`, `allowPrivilegeEscalation: false`, and `capabilities.drop: [ALL]`.

**Files:**
- Modify: `infrastructure/k8s/base/40-baas-engine.yaml`
- Modify: `infrastructure/k8s/base/50-baas-ncube.yaml`

- [ ] **Step 1: Add to both Deployments' `spec.template.spec`**

```yaml
spec:
  template:
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000     # matches Dockerfile USER app (uid 1000 in alpine)
        runAsGroup: 1000
        fsGroup: 1000
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: baas-engine   # or baas-ncube
          # ...existing fields...
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
```

(`readOnlyRootFilesystem: true` requires an `emptyDir` mounted at `/tmp` because Spring Boot writes temp files. The `volumes` block is added at pod-spec level.)

- [ ] **Step 2: Verify**

```bash
kubectl --dry-run=client apply -f infrastructure/k8s/base/40-baas-engine.yaml
kubectl --dry-run=client apply -f infrastructure/k8s/base/50-baas-ncube.yaml
```

- [ ] **Step 3: Commit**

```bash
git add infrastructure/k8s/base/40-baas-engine.yaml infrastructure/k8s/base/50-baas-ncube.yaml
git commit -m "fix(k8s): PodSecurityContext + container securityContext for both Deployments (1E I1)"
```

---

## Task 11 — Add NetworkPolicy as a Kustomize Component (opt-in per overlay)

Mandatory for PCI/NDPR/CBN scope. Default-deny + explicit ingress/egress for each pod role.

NetworkPolicies are packaged as a **Kustomize Component** rather than inlined into the base. This keeps dev overlays free of policies (so `kubectl port-forward` and ad-hoc debugging work without fiddling) while staging and prod opt in by listing the component. The policy file itself is identical across overlays — separation is at the wiring level, not the YAML level.

A Component (`kind: Component`) differs from a regular `kind: Kustomization` in that it is meant to be merged into another kustomization rather than rendered standalone. This is the canonical pattern for cross-cutting concerns that some — but not all — overlays should pull in.

**Files:**
- Create: `infrastructure/k8s/components/network-policy/kustomization.yaml`
- Create: `infrastructure/k8s/components/network-policy/15-network-policy.yaml`
- Modify: `infrastructure/k8s/overlays/staging/kustomization.yaml` (add component)
- Modify: `infrastructure/k8s/overlays/prod/kustomization.yaml` (add component)
- (do NOT modify dev overlay — dev intentionally has no NetworkPolicy)

- [ ] **Step 1: Write the policy manifest**

```yaml
---
# Default-deny: all pods in nubbank-baas accept no ingress and have no egress
# unless an explicit allow rule is matched below.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny
  namespace: nubbank-baas
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
---
# Allow: ingress controller → baas-engine
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-ingress-to-engine
  namespace: nubbank-baas
spec:
  podSelector: { matchLabels: { app: baas-engine } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector: { matchLabels: { name: ingress-nginx } }
      ports:
        - protocol: TCP
          port: 8080
---
# Allow: baas-engine → postgres
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-engine-to-postgres
  namespace: nubbank-baas
spec:
  podSelector: { matchLabels: { app: postgres } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: { matchLabels: { app: baas-engine } }
      ports:
        - protocol: TCP
          port: 5432
---
# Allow: baas-engine → baas-ncube
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-engine-to-ncube
  namespace: nubbank-baas
spec:
  podSelector: { matchLabels: { app: baas-ncube } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: { matchLabels: { app: baas-engine } }
      ports:
        - protocol: TCP
          port: 8082
---
# Allow: baas-ncube → external (NIBSS) on 443. DNS allowed via kube-system.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-ncube-egress
  namespace: nubbank-baas
spec:
  podSelector: { matchLabels: { app: baas-ncube } }
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector: { matchLabels: { name: kube-system } }
      ports:
        - protocol: UDP
          port: 53
    - to: []   # any external IP (NIBSS)
      ports:
        - protocol: TCP
          port: 443
---
# Allow: baas-engine egress (DNS, postgres, ncube, redis if used)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-engine-egress
  namespace: nubbank-baas
spec:
  podSelector: { matchLabels: { app: baas-engine } }
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector: { matchLabels: { name: kube-system } }
      ports:
        - protocol: UDP
          port: 53
    - to:
        - podSelector: { matchLabels: { app: postgres } }
      ports:
        - protocol: TCP
          port: 5432
    - to:
        - podSelector: { matchLabels: { app: baas-ncube } }
      ports:
        - protocol: TCP
          port: 8082
```

The file path is `infrastructure/k8s/components/network-policy/15-network-policy.yaml`. Body is identical to the YAML block above (six NetworkPolicy resources joined by `---`).

- [ ] **Step 2: Write the Component `kustomization.yaml`**

Create `infrastructure/k8s/components/network-policy/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1alpha1
kind: Component

resources:
  - 15-network-policy.yaml
```

Note: `kind: Component` is the critical bit — Kustomize treats this differently from `kind: Kustomization`, allowing it to be referenced from an overlay's `components:` field.

- [ ] **Step 3: Wire the component into staging overlay**

Edit `infrastructure/k8s/overlays/staging/kustomization.yaml` — append the `components:` block:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../../base

components:
  - ../../components/network-policy   # ← new

images:
  - name: ghcr.io/razormvp/baas-engine
    newTag: base-do-not-deploy
  - name: ghcr.io/razormvp/baas-ncube
    newTag: base-do-not-deploy
```

- [ ] **Step 4: Wire the component into prod overlay**

Edit `infrastructure/k8s/overlays/prod/kustomization.yaml` — same `components:` block. Dev overlay is left unchanged.

- [ ] **Step 5: Verify rendering**

```bash
cd infrastructure/k8s
# Component renders standalone (smoke test that YAML is valid).
kubectl --dry-run=client apply -f components/network-policy/15-network-policy.yaml

# Dev overlay must NOT contain any NetworkPolicy resources.
kubectl kustomize overlays/dev/ | grep -c 'kind: NetworkPolicy' || true
# Expected: 0

# Staging overlay must include all 6 NetworkPolicies.
kubectl kustomize overlays/staging/ | grep -c 'kind: NetworkPolicy'
# Expected: 6

# Prod overlay must also include all 6.
kubectl kustomize overlays/prod/ | grep -c 'kind: NetworkPolicy'
# Expected: 6
```

- [ ] **Step 6: Commit**

```bash
git add infrastructure/k8s/components/network-policy/ \
         infrastructure/k8s/overlays/staging/kustomization.yaml \
         infrastructure/k8s/overlays/prod/kustomization.yaml
git commit -m "feat(k8s): NetworkPolicy as Kustomize component, opt-in for staging+prod (1E I2)"
```

---

## Task 12 — Split `baas-ncube-secrets` from `baas-engine-secrets`

ncube must not have access to engine's `JWT_SECRET` and `ENCRYPTION_KEY`.

**Files:**
- Create: `infrastructure/k8s/base/17-baas-ncube-secrets.example.yaml`
- Modify: `infrastructure/k8s/base/50-baas-ncube.yaml`

- [ ] **Step 1: Write `17-baas-ncube-secrets.example.yaml`**

```yaml
# Template — DO NOT commit a populated copy.
# Generate via:
#   kubectl create secret generic baas-ncube-secrets \
#     --namespace nubbank-baas \
#     --from-literal=DATASOURCE_USERNAME=<CHANGE_ME> \
#     --from-literal=DATASOURCE_PASSWORD=<CHANGE_ME> \
#     --from-literal=INTERNAL_SERVICE_SECRET=<CHANGE_ME> \
#     --from-literal=NPS_SIGNING_KEY=<CHANGE_ME>      # NIBSS signing key
apiVersion: v1
kind: Secret
metadata:
  name: baas-ncube-secrets
  namespace: nubbank-baas
type: Opaque
stringData:
  DATASOURCE_USERNAME: <CHANGE_ME>
  DATASOURCE_PASSWORD: <CHANGE_ME>
  INTERNAL_SERVICE_SECRET: <CHANGE_ME>
  NPS_SIGNING_KEY: <CHANGE_ME>
```

(No `JWT_SECRET`, no `ENCRYPTION_KEY` — ncube doesn't need them.)

- [ ] **Step 2: Update `50-baas-ncube.yaml`**

Change:

```yaml
envFrom:
  - secretRef:
      name: baas-engine-secrets   # ← OLD
```

To:

```yaml
envFrom:
  - secretRef:
      name: baas-ncube-secrets   # ← NEW; only ncube-needed values
  - configMapRef:
      name: baas-ncube-config
```

- [ ] **Step 3: Update README.md** to document the two-secret pattern.

- [ ] **Step 4: Commit**

```bash
git add infrastructure/k8s/base/17-baas-ncube-secrets.example.yaml \
         infrastructure/k8s/base/50-baas-ncube.yaml \
         infrastructure/k8s/README.md
git commit -m "fix(k8s): split baas-ncube-secrets from engine secrets — least privilege (1E I3)"
```

---

## Task 13 — Add `startupProbe` + switch healthchecks to `/readiness`

Spring Boot 3 + Flyway can take 90+ seconds; `livenessProbe` with `initialDelaySeconds: 60` causes crash loops.

**Files:**
- Modify: `infrastructure/k8s/base/40-baas-engine.yaml`
- Modify: `infrastructure/k8s/base/50-baas-ncube.yaml`

- [ ] **Step 1: For each Deployment**, replace the existing probe block with:

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  failureThreshold: 30   # 30 × 5s = 150s grace
  periodSeconds: 5
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  periodSeconds: 30
  failureThreshold: 3
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3
```

(For ncube replace `8080` with `8082`.)

- [ ] **Step 2: Confirm `application.yml` has probe endpoints enabled**

Both `baas-engine/src/main/resources/application.yml` and `baas-ncube/src/main/resources/application.yml` should expose `health` (already done; verify). For Spring Boot 3, `/actuator/health/{liveness,readiness}` are auto-enabled when probes are detected (k8s deployment) but can be forced via:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
```

Add this line if not present.

- [ ] **Step 3: Verify**

```bash
kubectl --dry-run=client apply -f infrastructure/k8s/base/40-baas-engine.yaml
kubectl --dry-run=client apply -f infrastructure/k8s/base/50-baas-ncube.yaml
```

- [ ] **Step 4: Commit**

```bash
git add infrastructure/k8s/base/40-baas-engine.yaml infrastructure/k8s/base/50-baas-ncube.yaml \
         baas-engine/src/main/resources/application.yml \
         baas-ncube/src/main/resources/application.yml
git commit -m "fix(k8s): startupProbe + readiness probe paths for both Deployments (1E I5, I13)"
```

---

## Task 14 — Add PodDisruptionBudgets for engine, ncube, postgres

Prevents `kubectl drain` of multiple nodes from causing total outage.

**Files:**
- Create: `infrastructure/k8s/base/70-pod-disruption-budgets.yaml`

- [ ] **Step 1: Write the file**

```yaml
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: baas-engine-pdb
  namespace: nubbank-baas
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: baas-engine
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: baas-ncube-pdb
  namespace: nubbank-baas
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: baas-ncube
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: postgres-pdb
  namespace: nubbank-baas
spec:
  minAvailable: 1
  selector:
    matchLabels:
      app: postgres
```

- [ ] **Step 2: Commit**

```bash
git add infrastructure/k8s/base/70-pod-disruption-budgets.yaml
git commit -m "feat(k8s): PodDisruptionBudgets for engine, ncube, postgres (1E C4 partial, I10)"
```

---

## Task 15 — Document GHCR `imagePullSecrets`

GHCR packages can be private; first deploy fails silently with `ImagePullBackOff` if pull secrets aren't provisioned.

**Files:**
- Modify: `infrastructure/k8s/README.md`

- [ ] **Step 1: Append to README.md**

```markdown
## Pulling from GHCR (private images)

If the `ghcr.io/razormvp/baas-engine` package is set to private, every node needs a pull secret:

    kubectl create secret docker-registry ghcr-creds \
      --namespace nubbank-baas \
      --docker-server=ghcr.io \
      --docker-username=<GH_USER> \
      --docker-password=<GH_PAT_WITH_READ_PACKAGES_SCOPE>

Then add to both Deployments:

    spec:
      template:
        spec:
          imagePullSecrets:
            - name: ghcr-creds
```

- [ ] **Step 2: Commit**

```bash
git add infrastructure/k8s/README.md
git commit -m "docs(k8s): document GHCR imagePullSecrets workflow (1E I4)"
```

---

## Task 16 — Add Trivy + SBOM + provenance to CI workflows

Banking baseline: every image scanned for HIGH/CRITICAL CVEs, SBOM published, SLSA L1 provenance attestation.

**Files:**
- Modify: `.github/workflows/baas-engine-ci.yml`
- Modify: `.github/workflows/baas-ncube-ci.yml`

- [ ] **Step 1: For each workflow**, add a `security-scan` job that runs after `build-and-push`:

```yaml
  security-scan:
    needs: build-and-push
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - name: Trivy scan image
        uses: aquasecurity/trivy-action@<sha>   # pin to SHA per Task 17
        with:
          image-ref: ghcr.io/razormvp/baas-engine:sha-${{ github.sha }}
          format: sarif
          output: trivy-results.sarif
          severity: CRITICAL,HIGH
          exit-code: 1   # fail the job on any HIGH/CRITICAL
      - name: Upload SARIF
        uses: github/codeql-action/upload-sarif@<sha>
        with:
          sarif_file: trivy-results.sarif

      - name: Generate SBOM
        uses: anchore/sbom-action@<sha>
        with:
          image: ghcr.io/razormvp/baas-engine:sha-${{ github.sha }}
          format: spdx-json
          artifact-name: baas-engine-sbom-${{ github.sha }}.spdx.json
```

For provenance, add `provenance: true` to the existing `docker/build-push-action@<sha>` invocation in the `build-and-push` job.

- [ ] **Step 2: Repeat for `baas-ncube-ci.yml`** with appropriate image refs.

- [ ] **Step 3: Verify**

```bash
yamllint .github/workflows/*.yml || true
gh workflow view baas-engine-ci   # confirms YAML parses
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/baas-engine-ci.yml .github/workflows/baas-ncube-ci.yml
git commit -m "feat(ci): Trivy CVE scan + SBOM + provenance attestation (1E I6)"
```

---

## Task 17 — Pin GitHub Actions to commit SHAs

Mutable tags are a known supply-chain risk. Pin every `uses:` to a 40-char SHA with the version in a comment.

**Files:**
- Modify: `.github/workflows/baas-engine-ci.yml`
- Modify: `.github/workflows/baas-ncube-ci.yml`

- [ ] **Step 1: For each `uses:` in both workflows**, find the SHA for the current major version

```bash
# Example for actions/checkout@v4:
gh api repos/actions/checkout/git/refs/tags/v4 --jq '.object.sha'
# Output: <40-char-sha>
```

Repeat for: `actions/checkout`, `actions/setup-java`, `docker/login-action`, `docker/setup-buildx-action`, `docker/build-push-action`, `docker/metadata-action`, `aquasecurity/trivy-action`, `github/codeql-action`, `anchore/sbom-action`.

- [ ] **Step 2: Replace each `uses:`**

```yaml
- uses: actions/checkout@<40-char-sha>  # v4
```

- [ ] **Step 3: Verify both workflows still parse**

```bash
yamllint .github/workflows/*.yml || true
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/baas-engine-ci.yml .github/workflows/baas-ncube-ci.yml
git commit -m "fix(ci): pin all GitHub Actions to commit SHAs (1E I7)"
```

---

## Task 18 — Scope `packages: write` permission to build-and-push job only

`permissions: { packages: write }` at workflow level grants every job that scope.

**Files:**
- Modify: `.github/workflows/baas-engine-ci.yml`
- Modify: `.github/workflows/baas-ncube-ci.yml`

- [ ] **Step 1: Remove top-level `permissions:` block** (or set it to `contents: read` only) and add per-job:

```yaml
jobs:
  test:
    permissions:
      contents: read
    # ...

  build-and-push:
    permissions:
      contents: read
      packages: write   # only this job needs to push to GHCR
      id-token: write   # for provenance attestation
    # ...

  security-scan:
    permissions:
      contents: read
      security-events: write
    # ...
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/baas-engine-ci.yml .github/workflows/baas-ncube-ci.yml
git commit -m "fix(ci): scope packages: write to build-and-push job only (1E m10)"
```

---

## Task 19 — Bind Compose Postgres to 127.0.0.1 only

Default `5432:5432` binds 0.0.0.0 — exposes DB to LAN.

**Files:**
- Modify: `infrastructure/docker-compose.yml`
- Modify: `infrastructure/.env.example` (re-create if missing)

- [ ] **Step 1: Update docker-compose.yml**

Change:

```yaml
ports:
  - "${POSTGRES_PORT:-5432}:5432"
```

To:

```yaml
ports:
  - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"   # localhost-only; never exposed to LAN
```

- [ ] **Step 2: Update or create `.env.example`** with stronger placeholders:

```dotenv
# DO NOT commit a populated copy.
# Replace every <CHANGE_ME> before bringing the stack up.
POSTGRES_DB=nubbank_baas
POSTGRES_USER=baas
POSTGRES_PASSWORD=<CHANGE_ME>
POSTGRES_PORT=5432

JWT_SECRET=<CHANGE_ME>                  # ≥32 chars, generated via openssl rand -base64 48
ENCRYPTION_KEY=<CHANGE_ME>              # ≥32 chars
INTERNAL_SERVICE_SECRET=<CHANGE_ME>     # shared between engine and ncube; ≥32 chars

NIBSS_NPS_BASE_URL=https://nps-sandbox.nibss-plc.com.ng
NPS_LIVE=false
```

- [ ] **Step 3: Commit**

```bash
git add infrastructure/docker-compose.yml infrastructure/.env.example
git commit -m "fix(infra): bind Compose Postgres to 127.0.0.1 only; <CHANGE_ME> placeholders (1E I8, m2)"
```

---

## Task 20 — Add CODEOWNERS file

Require review for `infrastructure/**`, `.github/workflows/**`, and security-sensitive paths.

**Files:**
- Create: `.github/CODEOWNERS`

- [ ] **Step 1: Write the file**

```
# CODEOWNERS for nubbank-baas
# Review required from listed owners on every PR that touches matched paths.

# Infrastructure — manifests, Dockerfiles, CI workflows
/infrastructure/**           @RazorMVP
/.github/workflows/**        @RazorMVP
/baas-engine/Dockerfile      @RazorMVP
/baas-ncube/Dockerfile       @RazorMVP

# Security-sensitive code paths
/baas-engine/src/main/java/com/nubbank/baas/engine/config/**           @RazorMVP
/baas-engine/src/main/java/com/nubbank/baas/engine/tenant/**           @RazorMVP
/baas-engine/src/main/java/com/nubbank/baas/engine/audit/**            @RazorMVP
/baas-ncube/src/main/java/com/nubbank/baas/ncube/config/**             @RazorMVP

# Database migrations — once landed, never edited
/baas-engine/src/main/resources/db/migration/**                        @RazorMVP

# Regulatory + compliance docs
/docs/regulatory/**          @RazorMVP

# CODEOWNERS itself — meta-protection
/.github/CODEOWNERS          @RazorMVP
```

- [ ] **Step 2: Commit**

```bash
git add .github/CODEOWNERS
git commit -m "feat(governance): CODEOWNERS — require review on infra, workflows, security paths (1E m7)"
```

---

## Task 21 — Tune HPA target + lower priority cleanup

Most of the minor 1E findings (m1–m12) are tiny cosmetic fixes — bundle into a single cleanup commit.

**Files:**
- Modify: `infrastructure/k8s/base/40-baas-engine.yaml` (HPA target)
- Modify: `infrastructure/k8s/base/50-baas-ncube.yaml` (HPA target, memory limit if tight)
- Modify: `infrastructure/k8s/base/60-ingress.yaml` (TODO comment for host)
- Modify: `infrastructure/k8s/base/30-postgres.yaml` (m4: replace `$(POSTGRES_USER)` with `postgres`)
- Modify: each Dockerfile (m1: ENTRYPOINT explicit `/app/app.jar`, m5: `--start-period=120s` already done in Task 1)

- [ ] **Step 1: Lower HPA target to 60% in both Deployments**

```yaml
# in 40-baas-engine.yaml HorizontalPodAutoscaler block:
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 60   # was 70 — JVM saturates GC/heap before CPU
```

- [ ] **Step 2: Bump baas-ncube memory limit if tight**

```yaml
resources:
  limits:
    memory: 768Mi   # was 512Mi — ISO 20022 XML payloads can be large
```

- [ ] **Step 3: Add TODO comment in `60-ingress.yaml`**

```yaml
spec:
  rules:
    - host: api.nubbank.example.com   # TODO: replace with real host before deploy
```

- [ ] **Step 4: Commit**

```bash
git add infrastructure/k8s/base/40-baas-engine.yaml infrastructure/k8s/base/50-baas-ncube.yaml \
         infrastructure/k8s/base/60-ingress.yaml infrastructure/k8s/base/30-postgres.yaml \
         baas-engine/Dockerfile baas-ncube/Dockerfile
git commit -m "chore(infra): minor cleanup (HPA target, ncube memory, host TODO, postgres pg_isready) (1E m1-m12)"
```

---

## Task 22 — Final verification + branch merge

- [ ] **Step 1: Validate every base manifest + every overlay render**

```bash
# Each base file must be syntactically valid YAML.
for f in infrastructure/k8s/base/*.yaml; do
  echo "=== $f ==="
  kubectl --dry-run=client apply -f "$f"
done

# Component manifests (NetworkPolicy lives only here).
kubectl --dry-run=client apply -f infrastructure/k8s/components/network-policy/15-network-policy.yaml

# Each overlay must render without Kustomize errors.
for o in dev staging prod; do
  echo "=== render overlays/$o ==="
  kubectl kustomize "infrastructure/k8s/overlays/$o" > /dev/null
done

# Sanity: dev has 0 NetworkPolicies; staging+prod have 6.
[ "$(kubectl kustomize infrastructure/k8s/overlays/dev | grep -c 'kind: NetworkPolicy' || true)" = "0" ]
[ "$(kubectl kustomize infrastructure/k8s/overlays/staging | grep -c 'kind: NetworkPolicy')" = "6" ]
[ "$(kubectl kustomize infrastructure/k8s/overlays/prod | grep -c 'kind: NetworkPolicy')" = "6" ]
```

All should pass.

- [ ] **Step 2: Build both Docker images locally**

```bash
docker build -t baas-engine:phase1f-e -f baas-engine/Dockerfile baas-engine
docker build -t baas-ncube:phase1f-e -f baas-ncube/Dockerfile baas-ncube
```

Both succeed without errors.

- [ ] **Step 3: Compose smoke test**

```bash
cd infrastructure
cp .env.example .env
# Fill in <CHANGE_ME> values manually
docker compose up -d postgres
docker compose up -d baas-engine baas-ncube
docker compose ps   # all should show "healthy" within 2 minutes
```

- [ ] **Step 4: Update `baas-log.md`**

Add Session entry at top with full set of 1E findings closed (6 critical + 13 important + 13 minor), New/Updated Files table, Confirmed Platform Versions block.

- [ ] **Step 5: Update `CLAUDE.md`**

- Bump SHA in Confirmed Platform Versions header
- Add new gotchas: NetworkPolicy default-deny pattern, SHA-templated images, GHCR pull secrets

- [ ] **Step 6: Update `/baas` skill Phase Build Order**

Mark Phase 1F-E as ✅ in `/Users/razormvp/nubbank-baas/.claude/skills/baas/SKILL.md`.

- [ ] **Step 7: Push + open PR**

```bash
git push -u origin feature/phase1f-e-infra
gh pr create --title "Phase 1F-E: infrastructure hardening (1E C1-C6, I1-I14, m1-m12)" \
             --body "$(cat <<'EOF'
## Summary

Closes all 32 findings from the 1E retrospective infrastructure review (6 critical, 13 important, 13 minor).

### Dockerfile fixes
- curl in runtime image (replaces missing wget)
- Healthchecks use /actuator/health/readiness
- Deterministic jar (finalName=app)
- Maven cache split (dependency:go-offline before src copy)
- Base image pinned to digest
- JVM flags moved into ENTRYPOINT (not overridable env)
- .dockerignore added

### Kubernetes hardening
- Manifests restructured as Kustomize tree (base + dev/staging/prod overlays + opt-in components)
- :latest replaced with sentinel `:base-do-not-deploy` in base; overlays substitute SHA via Kustomize `images:`
- Missing baas-ncube-config ConfigMap declared
- Postgres SecurityContext + resources + livenessProbe
- PodSecurityContext + container SecurityContext on both Deployments
- NetworkPolicy default-deny + targeted allows packaged as Kustomize Component, opt-in for staging+prod (dev free of policies)
- baas-ncube-secrets split from engine secrets (least privilege)
- startupProbe + readiness/liveness path correctness
- PodDisruptionBudget for all three workloads
- GHCR imagePullSecrets documented

### CI hardening
- Trivy CVE scan + SBOM + SLSA provenance
- All actions pinned to commit SHAs
- packages: write scoped to build-and-push job only
- CODEOWNERS requires review on infra/security paths

### Compose fixes
- Postgres bound to 127.0.0.1 only

## Test plan

- [ ] All k8s manifests pass kubectl --dry-run=client
- [ ] Both Docker images build successfully
- [ ] docker compose up brings stack to healthy status
- [ ] CI runs Trivy and reports zero HIGH/CRITICAL
- [ ] CODEOWNERS block enforced on next PR
EOF
)"
```

After review and merge, mark Phase 1F-E ✅ in `/baas` skill and tag the merge commit `phase1f-e-merged`.

---

## Summary

| Task | Files changed | Findings closed |
|------|---------------|-----------------|
| 1 | 2 Dockerfiles + compose | 1E C1, I13 |
| 2 | 2 poms + 2 Dockerfiles | 1E C5 |
| 3 | 2 Dockerfiles | 1E C6 |
| 4 | 2 Dockerfiles | 1E I9 |
| 5 | 2 Dockerfiles | 1E I12 |
| 6 | 2 .dockerignore (new) | 1E I14 |
| 7 | k8s tree → base/ + 3 overlay kustomizations + README | 1E C2 |
| 8 | 1 new ConfigMap + manifest + base kustomization | 1E C3 |
| 9 | 1 manifest | 1E C4 |
| 10 | 2 manifests | 1E I1 |
| 11 | 1 new NetworkPolicy + Component kustomization + 2 overlay edits | 1E I2 |
| 12 | 1 new Secret + manifest | 1E I3 |
| 13 | 2 manifests + 2 yamls | 1E I5, I13 |
| 14 | 1 new PDB file | 1E I10, C4 |
| 15 | README | 1E I4 |
| 16 | 2 CI workflows | 1E I6 |
| 17 | 2 CI workflows | 1E I7 |
| 18 | 2 CI workflows | 1E m10 |
| 19 | docker-compose + .env.example | 1E I8, m2 |
| 20 | 1 new CODEOWNERS | 1E m7 |
| 21 | several manifests + Dockerfiles | 1E m1, m3, m4, m5, m8, m9 |
| 22 | docs + push + PR | (gates) |

**Total: 22 tasks, ~12 new files (incl. 5 Kustomize files), ~14 modified files, 28 findings closed (6 critical + 13 important + 9 minor).**

### Findings explicitly deferred (4 of 32)

| ID | Description | Deferral reason |
|----|-------------|-----------------|
| I11 | HPA scales on CPU only — JVM apps usually need request-rate or memory metric | Original review marked this "nice-to-have if KEDA isn't on the table". Defer until real load data exists. |
| m3 | cert-manager / external-dns workflow not documented in Ingress | Cluster-specific; documented as overlay pattern in README is sufficient for now. |
| m6 | `cache: maven` and `cache-from: type=gha` are independent caches | Documentation-only minor; no functional bug. |
| m11 | CI triggers on `pull_request` but `build-and-push` only runs on push | Documentation-only minor; current behaviour is correct. |

Estimated effort: 1–2 focused sessions. Independent of Plans 0/A/B — can run in parallel.
