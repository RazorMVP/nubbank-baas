# NubBank BaaS — Kubernetes Manifests

Vanilla Kubernetes deployment manifests for `baas-engine` and `baas-ncube`. Works on any conformant cluster — kind / minikube / k3s for local, EKS / GKE / AKS / DigitalOcean / on-prem clusters for production.

## Files

| File | Purpose |
| ---- | ------- |
| `00-namespace.yaml` | `nubbank-baas` namespace |
| `10-secrets.example.yaml` | Template for the `baas-engine-secrets` Secret. **Do not commit a populated copy.** |
| `20-configmap.yaml` | Non-secret runtime config (DATASOURCE_URL, profile, compliance override) |
| `30-postgres.yaml` | Reference Postgres StatefulSet — replace with your managed DB |
| `40-baas-engine.yaml` | Deployment + Service + HPA for the BaaS engine |
| `50-baas-ncube.yaml` | Deployment + Service for the CBN/NIBSS adapter |
| `60-ingress.yaml` | Ingress for `api.nubbank.example.com` — replace host as appropriate |

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
