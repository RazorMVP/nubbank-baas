# NubBank BaaS — Kubernetes Manifests

Vanilla Kubernetes deployment manifests for `baas-engine` and `baas-ncube`. Works on any conformant cluster — kind / minikube / k3s for local, EKS / GKE / AKS / DigitalOcean / on-prem clusters for production.

## Files

| File | Purpose |
|------|---------|
| `00-namespace.yaml` | `nubbank-baas` namespace |
| `10-secrets.example.yaml` | Template for the `baas-engine-secrets` Secret. **Do not commit a populated copy.** |
| `20-configmap.yaml` | Non-secret runtime config (DATASOURCE_URL, profile, compliance override) |
| `30-postgres.yaml` | Reference Postgres StatefulSet — replace with your managed DB |
| `40-baas-engine.yaml` | Deployment + Service + HPA for the BaaS engine |
| `50-baas-ncube.yaml` | Deployment + Service for the CBN/NIBSS adapter |
| `60-ingress.yaml` | Ingress for `api.nubbank.example.com` — replace host as appropriate |

## Deploy

```bash
# 1. Create the namespace and secrets first
kubectl apply -f 00-namespace.yaml

# 2. Generate the Secret from your KMS / Vault / SealedSecrets — do NOT use 10-secrets.example.yaml directly
kubectl create secret generic baas-engine-secrets \
  --namespace nubbank-baas \
  --from-literal=DATASOURCE_USERNAME=... \
  --from-literal=DATASOURCE_PASSWORD=... \
  --from-literal=JWT_SECRET=... \
  --from-literal=ENCRYPTION_KEY=... \
  --from-literal=INTERNAL_SERVICE_SECRET=...   # ≥32 chars; shared by engine + ncube for HMAC inter-service auth

# 3. Apply the rest
kubectl apply -f 20-configmap.yaml
kubectl apply -f 30-postgres.yaml          # skip if using a managed DB
kubectl apply -f 40-baas-engine.yaml
kubectl apply -f 50-baas-ncube.yaml
kubectl apply -f 60-ingress.yaml

# 4. Verify
kubectl -n nubbank-baas get pods
kubectl -n nubbank-baas logs -l app=baas-engine --tail=50
```

## Provider-specific overlays

These manifests are deliberately vanilla. Provider-specific config (NodeAffinity, PriorityClass, custom annotations like `service.beta.kubernetes.io/aws-load-balancer-type`, GCP Backend Configs, etc.) belongs in a `kustomization.yaml` overlay or Helm values file — never in the base.

Recommended layout:

```
infrastructure/k8s/                 # base — committed here
infrastructure/k8s/overlays/aws/    # AWS-specific overlay
infrastructure/k8s/overlays/gcp/    # GCP-specific overlay
infrastructure/k8s/overlays/onprem/ # on-prem-specific overlay
```

## Image registry

The deployments reference `ghcr.io/razormvp/baas-engine:latest` and `ghcr.io/razormvp/baas-ncube:latest`. CI publishes images on every merge to `main` (see `.github/workflows/`). To deploy a specific commit:

```bash
kubectl -n nubbank-baas set image deployment/baas-engine \
  baas-engine=ghcr.io/razormvp/baas-engine:5adeb10
```
