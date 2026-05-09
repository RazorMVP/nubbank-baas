# NubBank BaaS — Kubernetes Manifests

Vanilla Kubernetes deployment manifests for `baas-engine` and `baas-ncube`. Works on any conformant cluster — kind / minikube / k3s for local, EKS / GKE / AKS / DigitalOcean / on-prem clusters for production.

## Files

| File                                        | Purpose                                                                                                                                                                                                                                                        |
|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `base/00-namespace.yaml`                    | `nubbank-baas` namespace                                                                                                                                                                                                                                       |
| `base/10-secrets.example.yaml`              | Template for the `baas-engine-secrets` Secret (engine-only — JWT_SECRET, ENCRYPTION_KEY, etc.). **Documentation only — NOT included in `base/kustomization.yaml`.** Real secrets are created out-of-band via `kubectl create secret`, SealedSecrets, or Vault. |
| `base/17-baas-ncube-secrets.example.yaml`   | Template for the `baas-ncube-secrets` Secret (split from engine's secrets — least privilege). **Documentation only — NOT included in `base/kustomization.yaml`.**                                                                                              |
| `base/20-configmap.yaml`                    | Non-secret runtime config (DATASOURCE_URL, profile, compliance override)                                                                                                                                                                                       |
| `base/30-postgres.yaml`                     | Reference Postgres StatefulSet — replace with your managed DB                                                                                                                                                                                                  |
| `base/40-baas-engine.yaml`                  | Deployment + Service + HPA for the BaaS engine                                                                                                                                                                                                                 |
| `base/50-baas-ncube.yaml`                   | Deployment + Service for the CBN/NIBSS adapter                                                                                                                                                                                                                 |
| `base/60-ingress.yaml`                      | Ingress for `api.nubbank.example.com` — replace host as appropriate                                                                                                                                                                                            |

## Secrets layout (least privilege)

Two separate Secret resources, one per service:

| Secret                | Required by | Keys                                                                                                   |
|-----------------------|-------------|--------------------------------------------------------------------------------------------------------|
| `baas-engine-secrets` | engine only | DATASOURCE_USERNAME, DATASOURCE_PASSWORD, JWT_SECRET, ENCRYPTION_KEY, INTERNAL_SERVICE_SECRET          |
| `baas-ncube-secrets`  | ncube only  | DATASOURCE_USERNAME, DATASOURCE_PASSWORD, INTERNAL_SERVICE_SECRET, NPS_SIGNING_KEY                     |

`INTERNAL_SERVICE_SECRET` is the only key duplicated across both — engine uses it to sign outbound inter-service calls; ncube uses the same value to verify inbound. Both copies must hold the same value for HMAC verification to succeed.

Neither template is part of `base/kustomization.yaml`. Operators create them out-of-band via:

```bash
kubectl create secret generic baas-engine-secrets --namespace nubbank-baas --from-literal=...
kubectl create secret generic baas-ncube-secrets  --namespace nubbank-baas --from-literal=...
```

— or via SealedSecrets / SOPS / Vault / external-secrets-operator in production.

## Layout

```plaintext
infrastructure/k8s/
├── base/                — deployable manifests, no image SHAs
├── overlays/
│   ├── dev/             — dev-only image SHAs, no NetworkPolicy
│   ├── staging/         — staging SHAs + NetworkPolicy component (Task 11)
│   └── prod/            — prod SHAs + NetworkPolicy component (Task 11)
└── components/          — opt-in cross-cutting (added in Task 11)
```

The base manifests pin images to `:base-do-not-deploy`, a sentinel tag that does not exist
in GHCR. Direct `kubectl apply -f base/` therefore fails fast — an intentional safety guard.
Real deploys always go through an overlay.

## Deploying a specific commit

Substitute the image SHA into the chosen overlay, render, and apply:

```bash
SHA=$(git rev-parse HEAD)
cd infrastructure/k8s/overlays/prod
kustomize edit set image ghcr.io/razormvp/baas-engine=ghcr.io/razormvp/baas-engine:${SHA}
kustomize edit set image ghcr.io/razormvp/baas-ncube=ghcr.io/razormvp/baas-ncube:${SHA}
kubectl apply -k .
```

CI must commit `kustomization.yaml` back to `base-do-not-deploy` after deploy
(or work in a tempdir copy). Never check in a real SHA into the overlay file in git.

`kubectl rollout undo deployment/baas-engine -n nubbank-baas` revives the prior commit's image
because each Deployment revision retains its full pod spec including the SHA tag.

## Pulling from GHCR (private images)

If the `ghcr.io/razormvp/baas-engine` and `ghcr.io/razormvp/baas-ncube` packages are set to private (default for new GHCR repositories), every cluster node needs a pull secret. The first deploy will otherwise appear to succeed but pods will enter `ImagePullBackOff` and `kubectl describe pod` will show "pull access denied".

### One-time setup per cluster

```bash
kubectl create secret docker-registry ghcr-creds \
  --namespace nubbank-baas \
  --docker-server=ghcr.io \
  --docker-username=<GH_USER> \
  --docker-password=<GH_PAT_WITH_READ_PACKAGES_SCOPE>
```

The PAT requires `read:packages`. If the package is associated with a private repository (the default for new GitHub repos), the PAT also needs `repo` scope — GitHub ties package read access to repository read access in that case. For organization packages, ensure the PAT has SSO authorization for the org.

### Wiring the secret into Deployments

Two patterns:

**Per-Deployment (the plan's default):** add `imagePullSecrets:` to each Deployment's `spec.template.spec`:

```yaml
spec:
  template:
    spec:
      imagePullSecrets:
        - name: ghcr-creds
```

This is currently NOT applied in the base manifests. The existing comment in `base/40-baas-engine.yaml` explains why: the base manifests are deliberately vanilla k8s, with provider-specific config (NodeAffinity, PriorityClass, `imagePullSecrets`, etc.) layered in via Kustomize overlays or Helm values rather than baked into the base. To add the pull secret, create a Kustomize patch in your overlay (or a dedicated Component, mirroring the `network-policy` and `pod-disruption-budgets` patterns) — do not edit the base Deployment files directly.

**Via ServiceAccount (more idiomatic, DRY):** create a ServiceAccount with `imagePullSecrets` attached, then point every Deployment at that ServiceAccount. New Deployments inherit the secret automatically:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: baas-default
  namespace: nubbank-baas
imagePullSecrets:
  - name: ghcr-creds
```

Then on each Deployment:

```yaml
spec:
  template:
    spec:
      serviceAccountName: baas-default
```

Existing running pods do not pick up the new ServiceAccount automatically. After applying the SA + Deployment-spec change, run `kubectl -n nubbank-baas rollout restart deployment/baas-engine deployment/baas-ncube` to move pods onto the new SA.

Recommended for production deployments where new microservices may be added later.

### Public GHCR alternative

To skip the pull-secret entirely, set the GHCR packages to public via the GitHub UI: navigate to each package's settings page:

- `https://github.com/users/razormvp/packages/container/baas-engine/settings`
- `https://github.com/users/razormvp/packages/container/baas-ncube/settings`

Toggle "Change visibility" to public on each. No cluster-side change needed. Use this for sandbox/demo environments only.
