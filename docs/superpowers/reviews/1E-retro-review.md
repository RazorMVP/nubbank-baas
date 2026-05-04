# Phase 1E Retrospective Infrastructure Review

**Reviewed**: 2026-05-03
**Scope**: commit `17c2e3e` infrastructure files only (docs excluded)
**Files reviewed**: 11
- `baas-engine/Dockerfile`
- `baas-ncube/Dockerfile`
- `infrastructure/docker-compose.yml`
- `infrastructure/.env.example`
- `infrastructure/k8s/00-namespace.yaml`
- `infrastructure/k8s/10-secrets.example.yaml`
- `infrastructure/k8s/20-configmap.yaml`
- `infrastructure/k8s/30-postgres.yaml`
- `infrastructure/k8s/40-baas-engine.yaml`
- `infrastructure/k8s/50-baas-ncube.yaml`
- `infrastructure/k8s/60-ingress.yaml`
- `.github/workflows/baas-engine-ci.yml`
- `.github/workflows/baas-ncube-ci.yml`

**Reviewer**: superpowers:code-reviewer (retrospective, post-direct-commit-to-main; commit pushed to `main` with no PR, no peer review)

---

## Summary

Phase 1E delivers a clean, deployment-agnostic scaffolding (vanilla manifests, multi-stage Dockerfiles, multi-arch friendly base images, fail-fast secret discipline) that meets its stated goal of being runnable on any OCI runtime. However, the infrastructure has several show-stopping defects that will surface the moment it is actually exercised: the runtime image likely **does not contain `wget`**, breaking every healthcheck and causing the Compose stack to never reach `service_healthy`; every Kubernetes Deployment pulls `:latest` so deploys are non-deterministic and rollbacks are impossible; there is **zero** PodSecurityContext, NetworkPolicy, or PodDisruptionBudget on a regulated banking workload; and a referenced `baas-ncube-config` ConfigMap is never declared. There is no container scanning anywhere in CI. The scaffolding is roughly 70% there but should not be pointed at any environment more serious than a developer laptop until the Critical findings are closed.

---

## Critical findings

### C1. `wget` is not in the `eclipse-temurin:21-jre-alpine` runtime image — every healthcheck fails

- **Files**: `baas-engine/Dockerfile:34`, `baas-ncube/Dockerfile:27`, `infrastructure/docker-compose.yml:53` and `:77`
- **What's wrong**: Both Dockerfiles use `wget -q -O- http://127.0.0.1:8080/actuator/health` as the `HEALTHCHECK` and Compose uses the same command via `CMD-SHELL`. The Eclipse Temurin JRE Alpine image (`eclipse-temurin:21-jre-alpine`) **does not bundle `wget`** — only `busybox` shell utilities are guaranteed, and `wget` historically has been excluded from this slim image. (The full alpine base ships BusyBox `wget`, but Temurin's slimming may strip it.)
- **Failure scenario**: On first deploy the container starts, the JVM comes up, but Docker's healthcheck shell exits 127 (`wget: not found`) every interval. Status flips to `unhealthy`. In Compose, the `baas-engine` and `baas-ncube` services that depend on each other (and on Postgres) will never reach `service_healthy`. In Kubernetes the HTTP probes are unaffected (different mechanism), so this is a Docker/Compose-only outage — but ironically that's where developers will first encounter it.
- **Suggested fix**: Either (a) `RUN apk add --no-cache wget` in the runtime stage (cheap, ~150 KB), or (b) replace with a pure-shell TCP probe (`exec 3<>/dev/tcp/127.0.0.1/8080 && echo -e 'GET /actuator/health HTTP/1.0\r\n\r\n' >&3 && grep -q UP <&3`) — though `bash` isn't in alpine either, only `ash`. Cleanest answer: install `curl` (`apk add --no-cache curl`) and use `curl -fsS http://127.0.0.1:8080/actuator/health/readiness | grep -q UP`.
- **Also**: `/actuator/health` in Spring Boot 3.x with the default `management.endpoint.health.probes.enabled=true` returns `UP` even when the DB is `OUT_OF_SERVICE` if the readiness group isn't wired correctly. Use `/actuator/health/readiness` explicitly (which is what the k8s probes already use — be consistent).

### C2. Both Kubernetes Deployments pin to `:latest` — non-deterministic deploys, no rollback

- **Files**: `infrastructure/k8s/40-baas-engine.yaml:31`, `infrastructure/k8s/50-baas-ncube.yaml:26`
- **What's wrong**: `image: ghcr.io/razormvp/baas-engine:latest` combined with `imagePullPolicy: IfNotPresent`. Two failure modes:
  1. **Old pods never update**: `IfNotPresent` means the kubelet will never re-pull `:latest` once the image is cached on a node. New pods scheduled onto warm nodes will continue running an arbitrary historical version; new nodes will get the current `:latest`. The cluster ends up running multiple versions concurrently.
  2. **No rollback path**: `kubectl rollout undo` revives the same Deployment spec, which still points at `:latest` — there is no previous image SHA to revive.
- **Exploitation/failure scenario**: An attacker who compromises the GHCR push token can replace `:latest` with a malicious image. All future pod evictions silently pull the bad image. There is no audit trail in the manifest of what version is running.
- **Suggested fix**: Never reference `:latest` in any manifest. CI should output the SHA tag to a file (or write a kustomize patch / Helm values file), and the deploy step (kubectl/Helmfile/ArgoCD) should pin to a specific SHA. The manifest can use a placeholder like `IMAGE_TAG_PLACEHOLDER` that's templated at deploy time, or use `kubectl set image deployment/baas-engine baas-engine=ghcr.io/.../baas-engine:<sha>` as the README already shows. Set `imagePullPolicy: Always` if SHA tagging is truly enforced; otherwise leave `IfNotPresent` — but with SHA tags both work correctly.

### C3. Referenced ConfigMap `baas-ncube-config` is never defined — Deployment will not start

- **File**: `infrastructure/k8s/50-baas-ncube.yaml:36-40`
- **What's wrong**: The Deployment references `configMapKeyRef: name: baas-ncube-config`. There is no `baas-ncube-config` ConfigMap manifest anywhere in `infrastructure/k8s/`. The `optional: true` flag means the pod will start, but `NIBSS_NPS_BASE_URL` will be **unset**, falling back to whatever default (or null) baas-ncube assumes. Given the comment in `.env.example` that this URL must point at NIBSS sandbox/prod, an unset value silently means baas-ncube will attempt to reach `null/...` or its hardcoded `http://stub.local` default — which will never reach NIBSS in a real cluster.
- **Failure scenario**: Pod runs healthy, every NIBSS call silently fails or hits the stub, and the bug is invisible in the health endpoint. Production traffic is silently lost.
- **Suggested fix**: Either (a) create a `baas-ncube-config` ConfigMap manifest in this same file, or (b) move `NIBSS_NPS_BASE_URL` into the existing `baas-engine-config` ConfigMap (rename it `baas-config`). Drop `optional: true` so pods fail to start without it — the whole point of fail-fast.

### C4. Postgres pod has no SecurityContext, no resource limits, no liveness probe

- **File**: `infrastructure/k8s/30-postgres.yaml`
- **What's wrong**:
  - No `securityContext` on the pod or container. Postgres image default user is `postgres` UID 70, which is fine, but `runAsNonRoot: true`, `readOnlyRootFilesystem: false` (postgres needs writes — set explicitly to make intent clear), `allowPrivilegeEscalation: false`, `capabilities: drop: [ALL]`, and `seccompProfile: { type: RuntimeDefault }` are all absent. Pod Security Admission's `restricted` policy will reject this manifest in any cluster running PSA.
  - No `resources.requests` or `resources.limits` — single noisy neighbor can starve the whole node, or postgres can OOM-kill itself.
  - No `livenessProbe` — only `readinessProbe`. A wedged Postgres process will never restart automatically.
  - Single `replicas: 1` with no PodDisruptionBudget — `kubectl drain` of the node hosting it = full database outage.
- **Failure scenario**: PSA-enforced cluster (most modern managed offerings) refuses to admit the pod. Or, a memory leak / runaway query brings down both the DB and the engine pods on the same node.
- **Suggested fix**: Add a `securityContext` block (pod and container), `resources` block (e.g. `requests: { cpu: 250m, memory: 512Mi }, limits: { memory: 2Gi }` — leave CPU unlimited), a `livenessProbe` (also `pg_isready`), and a `PodDisruptionBudget { minAvailable: 1 }`. Add a doc note that for production, the StatefulSet should be replaced with a managed DB (already noted in the file comment, but worth strengthening).

### C5. `target/*.jar` glob is fragile — Spring Boot produces `app.jar` and `app.jar.original`

- **Files**: `baas-engine/Dockerfile:29`, `baas-ncube/Dockerfile:22`
- **What's wrong**: `COPY --from=build /workspace/target/*.jar app.jar` — when Spring Boot's `spring-boot-maven-plugin` runs with `repackage` (the default `package` phase), Maven leaves **two** jars in `target/`: `app-0.0.1-SNAPSHOT.jar` (the executable fat-jar) and `app-0.0.1-SNAPSHOT.jar.original` (the thin pre-repackaged jar). The wildcard `*.jar` matches only files ending in `.jar`, so `*.jar.original` is excluded — which means this works **today**. However:
  1. If `pom.xml` produces a `-sources.jar` or `-javadoc.jar` (common in shared-library modules), the glob would match multiple files and `COPY` would fail with `multiple sources copied to single destination`.
  2. The glob silently picks whichever jar comes first alphabetically — there is no guarantee it's the executable one.
- **Failure scenario**: Adding a `maven-source-plugin` or `maven-javadoc-plugin` (common when publishing to internal Nexus) breaks the build months from now with no obvious connection to the Dockerfile.
- **Suggested fix**: Use the Spring Boot plugin's `finalName` or rename via `mvn -B -DskipTests -Dspring-boot.repackage.outputFileNameMapping=true package`, then `COPY --from=build /workspace/target/app.jar app.jar`. Or: `RUN cp target/*-SNAPSHOT.jar /tmp/app.jar` in the build stage and copy from `/tmp` (specific name).

### C6. Maven cache layer invalidates on every source change — slow rebuilds

- **Files**: `baas-engine/Dockerfile:21-23`, `baas-ncube/Dockerfile:15-17`
- **What's wrong**: The Dockerfile does `COPY pom.xml ./` followed immediately by `COPY src ./src` and then `mvn package`. There is no intermediate `mvn dependency:go-offline` step. While `--mount=type=cache,target=/root/.m2` somewhat mitigates this, it's a BuildKit-specific cache that doesn't persist across CI runners by default (despite `cache-from: type=gha` in CI, the inline cache for the RUN step is not the same as the layer cache). Every code change re-resolves all dependencies inside the build.
- **Failure scenario**: 5–10 minute CI builds instead of 1–2 minutes.
- **Suggested fix**: Standard pattern:
  ```dockerfile
  COPY pom.xml ./
  RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
  COPY src ./src
  RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
  ```

---

## Important findings

### I1. No PodSecurityContext on either Deployment

- **Files**: `40-baas-engine.yaml`, `50-baas-ncube.yaml`
- **Issue**: Neither Deployment specifies `securityContext`. While the Dockerfile creates an `app` user and does `USER app`, that's a build-time choice the kubelet never validates. In any cluster running PSA `restricted`, both pods will be rejected. Equally important: no `readOnlyRootFilesystem`, no `allowPrivilegeEscalation: false`, no `capabilities.drop: [ALL]`, no `seccompProfile`.
- **Fix**: Add to `template.spec`:
  ```yaml
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: ...
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: [ALL]
  ```
  `readOnlyRootFilesystem: true` will require an `emptyDir` volume mounted at `/tmp` for Spring Boot's temp dirs — flag explicitly.

### I2. No NetworkPolicy at all

- **Files**: all of `infrastructure/k8s/`
- **Issue**: With no NetworkPolicy, every pod in `nubbank-baas` can talk to every other pod, and to every pod in every other namespace, and out to the internet. For a banking workload with PII, this is unacceptable. An attacker who compromises baas-ncube (NIBSS-facing, larger attack surface) gets unrestricted network reach to baas-engine and Postgres.
- **Fix**: Add a `default-deny` NetworkPolicy plus targeted allow rules: ingress controller → baas-engine, baas-engine → postgres, baas-engine → baas-ncube, baas-ncube → NIBSS (egress). Mark this as a hard requirement before any prod deploy.

### I3. baas-ncube reuses baas-engine's secrets — least-privilege violation

- **File**: `50-baas-ncube.yaml:33`
- **Issue**: `envFrom: secretRef: { name: baas-engine-secrets }` injects `JWT_SECRET` and `ENCRYPTION_KEY` into baas-ncube. baas-ncube does not (and should not) need either. If baas-ncube is ever compromised, the JWT signing key and PII encryption key for the entire engine are exposed.
- **Fix**: Create a separate `baas-ncube-secrets` Secret with only the values baas-ncube needs (NIBSS API credentials, signing key for NIBSS messages, Postgres creds — those should ideally be a dedicated DB user with limited privileges).

### I4. No `imagePullSecrets` for GHCR

- **Files**: `40-baas-engine.yaml`, `50-baas-ncube.yaml`
- **Issue**: GHCR images can be public or private. If the team makes the package private (default for new packages), every node needs `imagePullSecrets`. There is no documentation of this in the README. First deploy to a cluster will silently fail with `ImagePullBackOff`.
- **Fix**: Document the `kubectl create secret docker-registry ghcr-creds ...` step, or make the GHCR package public and document that.

### I5. Liveness probe will cause crash loops on slow startup

- **Files**: `40-baas-engine.yaml:53-59`, `50-baas-ncube.yaml:55-61`
- **Issue**: `livenessProbe` with `initialDelaySeconds: 60` is reasonable, but Spring Boot 3 with Flyway migrations on a fresh DB can take 90+ seconds on small node sizes. There's no `startupProbe` — once the 60s liveness delay elapses, kubelet starts checking liveness regardless of whether startup is complete. If startup takes longer, kubelet kills the pod.
- **Fix**: Add a `startupProbe` with `failureThreshold: 30` and `periodSeconds: 5` (so 150 seconds of grace) hitting `/actuator/health/liveness`. Liveness and readiness probes can then assume the JVM is fully booted.

### I6. CI workflows have no container vulnerability scanning

- **Files**: `.github/workflows/baas-engine-ci.yml`, `.github/workflows/baas-ncube-ci.yml`
- **Issue**: Trivy / Grype / Snyk image scanning is absent. SBOM generation is absent. Provenance attestation (`docker/build-push-action` supports `provenance: true`) is absent. For a banking workload, this is well below industry baseline — every image pushed to GHCR is unscanned.
- **Fix**: Add a Trivy job that runs after `build-and-push` and fails on `CRITICAL,HIGH`. Generate SBOM with `anchore/sbom-action` or `docker/build-push-action`'s built-in `sbom: true`. Enable `provenance: true` for SLSA L1.

### I7. CI uses `actions/checkout@v4` without commit SHA pin

- **Files**: both CI workflows
- **Issue**: All third-party actions are pinned to major-tag (`@v4`, `@v3`, `@v5`). Tags are mutable. A compromised maintainer (see `tj-actions/changed-files` 2025 incident) can replace a tag and exfiltrate `GITHUB_TOKEN`. Banking CI should pin to immutable commit SHAs.
- **Fix**: Replace every `uses: foo/bar@vN` with `uses: foo/bar@<40-char-sha>  # vN`. Renovate / Dependabot can keep these up to date.

### I8. Postgres exposed on host port 5432 in Compose

- **File**: `infrastructure/docker-compose.yml:24-25`
- **Issue**: `ports: - "${POSTGRES_PORT:-5432}:5432"` publishes Postgres on the developer's host. On a laptop this binds 0.0.0.0 by default (not just 127.0.0.1), exposing the database to the LAN — and on a poorly-configured corporate network, to the wider internet. Combined with `POSTGRES_PASSWORD: replace-me-with-a-strong-password` which many devs will forget to change, this is a classic compromise vector.
- **Fix**: Change to `127.0.0.1:${POSTGRES_PORT:-5432}:5432` so the port only binds the loopback interface. Add a comment in `.env.example` warning that this is dev-only.

### I9. JAVA_TOOL_OPTIONS is settable from env — runtime hardening bypass

- **Files**: both Dockerfiles, line 37/28
- **Issue**: `ENV JAVA_TOOL_OPTIONS="..."` is read by the JVM from the environment on every start. Any process that can set env vars on the container (a malicious `kubectl exec`, an env-leak from a downstream service) can override this with `-Dcom.sun.management.jmxremote=true` (open JMX), `-Djdk.attach.allowAttachSelf=true`, etc. Worse, `JAVA_TOOL_OPTIONS` content is logged at JVM startup.
- **Fix**: Bake settings into the ENTRYPOINT itself: `ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]`. The env var is fine for *adding* options at runtime, but the security baseline shouldn't rely on it.

### I10. No PodDisruptionBudget on baas-engine despite HPA min=2

- **File**: `40-baas-engine.yaml`
- **Issue**: HPA has `minReplicas: 2`, but a `kubectl drain` of two nodes simultaneously (rolling node upgrade, common in managed clusters) can evict both pods at once. No PDB = brief total outage.
- **Fix**: Add `PodDisruptionBudget { minAvailable: 1 }` for both Deployments.

### I11. HPA scales on CPU only — JVM apps usually need request-rate or memory

- **File**: `40-baas-engine.yaml:88-95`
- **Issue**: A Java app under load typically saturates GC / heap before CPU; or saturates request queue before either. CPU-only HPA scales too late, leading to latency spikes.
- **Fix**: Add `memory` as a second metric, or — better — use a custom metric (`http_requests_per_second`, `jvm_threads_live`) via Prometheus Adapter. Mark as nice-to-have if KEDA isn't on the table.

### I12. `eclipse-temurin:21-jre-alpine` is a floating tag — supply chain risk

- **Files**: both Dockerfiles, line 26/19
- **Issue**: No digest pin. `temurin:21-jre-alpine` can shift overnight (e.g. when JDK 21.0.6 lands). A reproducible build of an old commit will produce a different image. Also: any compromise of the Temurin Docker Hub account compromises all builds.
- **Fix**: Pin to digest in production: `FROM eclipse-temurin:21-jre-alpine@sha256:...`. Renovate can keep digests current.

### I13. Spring Boot health endpoint may return UP even with DB issues

- **Files**: both Dockerfiles `HEALTHCHECK`, Compose healthchecks
- **Issue**: `/actuator/health` (used by Docker `HEALTHCHECK`) returns the aggregate of all health indicators by default. The k8s probes correctly use `/actuator/health/readiness` and `/actuator/health/liveness`. The Docker healthcheck should also use `readiness` (DB connectivity included) — otherwise a DB outage doesn't flip the container to unhealthy in Compose.
- **Fix**: Change all healthchecks to `/actuator/health/readiness`. Confirm `management.endpoint.health.probes.enabled=true` in `application.yml`.

### I14. No `.dockerignore` files in baas-engine/baas-ncube

- **Files**: implied missing — neither `baas-engine/.dockerignore` nor `baas-ncube/.dockerignore` referenced
- **Issue**: `COPY pom.xml ./` and `COPY src ./src` are scoped, so the build context still includes everything in `baas-engine/` (target/, .idea/, .git/, .env files, IDE caches). This bloats build context, slows uploads, and can leak credentials if someone accidentally drops a `.env` file in the project root. CI will silently include `target/` from a previous local build.
- **Fix**: Add `.dockerignore` excluding `target/`, `.git`, `.idea`, `*.iml`, `.env*`, `.vscode`, `docs/`, `*.md`, `.mvn/wrapper/maven-wrapper.jar` (if it's regenerated).

---

## Minor findings

- **m1**: `baas-engine/Dockerfile:38` — `ENTRYPOINT ["java", "-jar", "app.jar"]` works, but consider `["java", "-jar", "/app/app.jar"]` to be explicit (WORKDIR sets `/app` so it works either way).
- **m2**: `infrastructure/.env.example:7` — `POSTGRES_PASSWORD=replace-me-with-a-strong-password` — the placeholder string is a valid Postgres password and devs may forget to replace it. Use `<CHANGE_ME_BEFORE_USE>` so it's syntactically obvious that it's not a real value.
- **m3**: `infrastructure/k8s/60-ingress.yaml` — no `cert-manager.io/cluster-issuer` annotation, no commentary about how `baas-engine-tls` Secret is provisioned. Document the cert-manager / external-dns workflow.
- **m4**: `30-postgres.yaml:58` — `pg_isready -U $(POSTGRES_USER)` — k8s env-var substitution syntax `$(VAR)` works but only because `POSTGRES_USER` is defined earlier in the same `env:` block. Brittle; use `pg_isready -U postgres` or `pg_isready -U $POSTGRES_USER` (shell expansion in `["sh", "-c", "..."]`).
- **m5**: Dockerfiles have `HEALTHCHECK --start-period=60s` — Spring Boot 3 with Flyway migrations and 50+ Hibernate entities can take longer on first boot. Bump to 90–120s.
- **m6**: CI workflow uses `cache: maven` from `setup-java@v4` and also `cache-from: type=gha` from `build-push-action` — these are independent caches; not wrong, but the maven cache only helps the test job, not the docker-build job.
- **m7**: No CODEOWNERS file enforcing review on `infrastructure/**` and `.github/workflows/**` paths. The fact that this commit landed on main with no review proves the gap.
- **m8**: HPA `metrics.resource.cpu.target.averageUtilization: 70` — for a JVM with `MaxRAMPercentage=75`, 70% CPU is reached late. 50–60% is more typical.
- **m9**: `baas-ncube` resource limit `memory: 512Mi` with `MaxRAMPercentage=75` of that = 384 MiB heap; depending on what NIBSS payloads look like (ISO 20022 XML can be large) this may be tight. Worth load-testing before fixing the value.
- **m10**: Workflow `permissions:` block sets `packages: write` at the workflow level, granting write to both `test` and `build-and-push` jobs — only `build-and-push` needs it. Move `permissions: { packages: write }` into the `build-and-push` job.
- **m11**: Both CI workflows trigger on `pull_request` even though `build-and-push` is gated to push events. Tests on PRs is fine — call this out so it's intentional, not accidental over-triggering.
- **m12**: `60-ingress.yaml` host placeholder `api.nubbank.example.com` is fine, but mark with `# TODO: replace with real host before deploy` so it's grep-able.

---

## Strengths

- **Multi-stage Dockerfile is correct in shape**: build stage on Maven JDK, runtime on JRE — minimises final image surface. Non-root user (`app:app`) is created and `USER app` is set. WORKDIR is owned correctly via `chown`.
- **Fail-fast secrets discipline**: `.env.example` documents that there are *no defaults* and the engine refuses to start without `JWT_SECRET`/`ENCRYPTION_KEY`/DB creds. The `10-secrets.example.yaml` correctly uses `REPLACE_ME` placeholders and the README emphasises generating secrets out-of-band.
- **GHCR token lowercasing handled correctly** — the bash `tr '[:upper:]' '[:lower:]'` step in both workflows avoids the well-known `RazorMVP` → OCI lowercase trap (called out as a gotcha in the parent CBA repo's CLAUDE.md).
- **Genuinely deployment-agnostic base manifests**: no AWS/GCP/Azure annotations, no LoadBalancer services, no provider-specific StorageClass — the README correctly directs cloud-specific config to overlays. This is exactly what was promised.
- **CI does not deploy** — the build/push/deploy separation is clean, lets target clusters control rollout via kubectl/Helmfile/ArgoCD, and avoids long-lived cluster credentials in GitHub Actions secrets.

---

## Recommended next actions

### Before Phase 1C (i.e. immediately)

1. **C1** — Install `wget` or `curl` in both runtime images, or replace healthcheck with TCP probe. Without this, Phase 1C contributors cannot use Compose locally.
2. **C3** — Define the missing `baas-ncube-config` ConfigMap (or fold `NIBSS_NPS_BASE_URL` into the existing config). The current state is a latent silent-failure bug.
3. **C5** — Either rename the jar in pom.xml to a deterministic name or update the `COPY` to a specific path. Future modules may add `-sources.jar`.
4. **C6** — Split out `mvn dependency:go-offline` to fix CI build times before the codebase grows.
5. **m7** — Add a `.github/CODEOWNERS` requiring review on `infrastructure/**` and `.github/workflows/**`. The fact that 1E landed unreviewed must not happen again on these paths.

### Before first prod deploy

6. **C2** — Stop pinning `:latest` in manifests. Either templated SHA tags or a deploy-time `kubectl set image`. Document the chosen pattern in the k8s README.
7. **C4** — Harden the Postgres StatefulSet (SecurityContext, resource limits, livenessProbe, PDB). Or, better, document that prod must use a managed DB and remove the StatefulSet from the production-applied set.
8. **I1** — Add PodSecurityContext + container securityContext to both Deployments. PSA `restricted` will reject these pods otherwise.
9. **I2** — Author and apply NetworkPolicies (default-deny + targeted allows). Mandatory for PCI / NDPR / CBN scope.
10. **I3** — Split `baas-ncube-secrets` from `baas-engine-secrets`.
11. **I4** — Document GHCR pull secrets (or make package public).
12. **I5** — Add `startupProbe` to both Deployments to handle slow Flyway migrations.
13. **I6** — Add Trivy + SBOM + provenance attestation to both CI workflows. Banking baseline.
14. **I8** — Bind Compose Postgres to `127.0.0.1` only.
15. **I9** — Move JVM hardening flags into ENTRYPOINT, not env.

### Before public sandbox launch

16. **I7** — Pin all GitHub Actions to commit SHAs. A 2025-era supply chain compromise would otherwise read `GITHUB_TOKEN` and gain `packages: write` to GHCR.
17. **I10** — Add PodDisruptionBudgets.
18. **I12** — Pin base image digests, set up Renovate to update them.
19. **I13** — Switch all healthchecks to `/actuator/health/readiness`.
20. **m3** — Wire cert-manager / external-dns into the Ingress flow with documented overlays.

### Nice to have

21. **I11** — Add memory metric (or custom request-rate metric) to HPA.
22. **m8/m9** — Load-test both services and tune resource limits / HPA thresholds based on real numbers.
23. **m10** — Scope `packages: write` permission to the `build-and-push` job only.
24. **I14** — Add `.dockerignore` files to both modules.
25. **m2** — Make placeholder values in `.env.example` syntactically obvious (`<CHANGE_ME>` style).
