# Full-Fidelity k8s Verification — NetworkPolicy Enforcement, FEP TCP LoadBalancer, Ingress Routing

> **Status:** verification playbook (not yet executed). Captures exactly what is required to runtime-verify the
> three capabilities a vanilla `kind` smoke test could **not** cover.
> **Date:** 2026-06-07
> **Context:** Follows the Session 13 k8s work (`baas-card` + `baas-fep` manifests, PR #19) and the kind smoke
> test that verified service rollout, Flyway `fep`-schema creation, and inter-service `:80` connectivity
> (with a negative control on `:8080`). The PGDATA reference-stub fix (PR #21) was also surfaced by that smoke.

---

## Why these three are still unverified

The blocker is the same for all three: a vanilla **kind** cluster ships a no-frills CNI (`kindnet`) and has no
cloud LoadBalancer provider or Ingress controller. Each capability needs a specific add-on:

| Capability | Why kind can't test it | Add-on required |
|---|---|---|
| NetworkPolicy enforcement | `kindnet` accepts NetworkPolicy objects but does **not** enforce them | Calico or Cilium (policy-enforcing CNI) |
| FEP TCP LoadBalancer (8583) | kind never assigns `LoadBalancer` external IPs (`EXTERNAL-IP: <pending>`) | `cloud-provider-kind` **or** MetalLB |
| Ingress routing | the `Ingress` object is inert without a controller | ingress-nginx (+ port-mapped cluster) |

What the smoke test *did* establish (for reference): the prod overlay's 35 objects all pass **server-side dry-run**
(API-valid, incl. all 16 NetworkPolicies), all four services + postgres reach Ready, Flyway auto-creates the
`fep` schema with `authorization_log`, and `:80` Service routing works (card→engine, fep→card, engine→ncube),
with `:8080` correctly timing out as a negative control.

---

## 1. NetworkPolicy enforcement (the 16 policies)

**Why unverified:** `kindnet` ignores NetworkPolicy — every pod can reach every pod regardless. The smoke proved
the 16 policies are *valid*, never that they *block*.

### What's needed — explicitly

1. **A policy-enforcing CNI** — Calico or Cilium. Calico is the lower-friction choice.
2. **A kind cluster with the default CNI disabled** so Calico owns networking:

   ```yaml
   # kind-calico.yaml
   kind: Cluster
   apiVersion: kind.x-k8s.io/v1alpha4
   networking:
     disableDefaultCNI: true        # remove kindnet
     podSubnet: "10.244.0.0/16"     # keep 10.x — see gotcha below
   ```

   `kind create cluster --config kind-calico.yaml`
3. **Install Calico** (pin the version), then wait for `calico-node` Ready:

   ```bash
   kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.x/manifests/calico.yaml
   ```
4. **Deploy the `staging` or `prod` overlay** — those include the `network-policy` component; `dev` deliberately omits it.
5. **Run positive + negative probes** — the negative ones are the entire point:
   - **Positive (must SUCCEED):** `card → engine:8080`, `fep → card:8081`, `engine → ncube:8082`, and
     `{card,fep,engine} → postgres:5432`.
   - **Negative (must be BLOCKED → time out):**
     - a debug pod with no matching policy → `engine:8080` (proves default-deny),
     - `ncube → postgres:5432` (no allow rule exists → should be denied),
     - `fep → engine:8080` (not an allowed edge).

### Gotchas specific to our manifests

- `allow-engine-egress` / `allow-ncube-egress` external-443 rules use
  `ipBlock: 0.0.0.0/0 except [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16]`. **The pod CIDR must fall inside one of
  those `except` ranges** (hence keep `10.244.0.0/16`), or in-cluster egress is misclassified as "external".
  Calico's *default* pod CIDR is `192.168.0.0/16`, which overlaps the `except` and muddies the test — force `10.244.0.0/16`.
- The DNS egress rule targets `namespaceSelector: kubernetes.io/metadata.name: kube-system` — auto-labeled since
  k8s 1.21, so fine.
- `allow-ingress-to-*` rules select `namespaceSelector: kubernetes.io/metadata.name: ingress-nginx` — those edges
  are only exercised if ingress-nginx is also installed (see §3).

---

## 2. FEP TCP LoadBalancer (ISO 8583 on 8583)

**Why unverified:** kind doesn't provision `LoadBalancer` external IPs — `baas-fep-tcp` stays at
`EXTERNAL-IP: <pending>` forever.

### What's needed — explicitly (pick one)

- **Option A — `cloud-provider-kind`** (simplest): `go install sigs.k8s.io/cloud-provider-kind@latest`, then run the
  `cloud-provider-kind` binary on the host alongside the cluster. It watches `LoadBalancer` Services and assigns IPs
  routed through the kind docker network.
- **Option B — MetalLB:** install MetalLB, then `docker network inspect kind` to read the subnet and create an
  `IPAddressPool` + `L2Advertisement` carving a range out of it.

### The actual test

1. `kubectl -n nubbank-baas get svc baas-fep-tcp` → confirm it now has an `EXTERNAL-IP`.
2. From the host, **TCP-connect** to `<external-ip>:8583` — minimally `nc -vz <ip> 8583` proves the Netty listener
   accepts connections.
3. **Real test:** send an ISO 8583 **`0800`** network-management / echo and expect an **`0810`** response — i.e., point
   the project's own terminal/test client (or jpos `q2`) at `<ip>:8583`. That proves the socket server parses and
   responds, not just that the port is open.

### Gotchas specific to our manifest

- We set `externalTrafficPolicy: Local` (to preserve the acquirer source IP for allowlisting + audit). To actually
  *verify* source-IP preservation, check what FEP logs as the client IP — with `Local` it should be the real host IP,
  not a SNAT'd node IP. `cloud-provider-kind` and MetalLB-L2 honor this differently, so confirm in FEP's logs.
- In production this Service must carry `loadBalancerSourceRanges` (acquirer CIDRs) — worth adding to the test to
  confirm the allowlist behaves.

---

## 3. Ingress routing (incl. the partner→card carve-out)

**Why unverified:** the `Ingress` object exists but is inert without a controller.

### What's needed — explicitly

1. **A kind cluster with ingress port-mappings + node label** (must be set at *create* time):

   ```yaml
   nodes:
   - role: control-plane
     kubeadmConfigPatches:
     - |
       kind: InitConfiguration
       nodeRegistration:
         kubeletExtraArgs:
           node-labels: "ingress-ready=true"
     extraPortMappings:
     - { containerPort: 80,  hostPort: 80 }
     - { containerPort: 443, hostPort: 443 }
   ```
2. **Install ingress-nginx (kind variant)**, then wait for the controller Ready:

   ```bash
   kubectl apply -f https://kind.sigs.k8s.io/examples/ingress/deploy-ingress-nginx.yaml
   ```
3. **Make our Ingress selectable + reachable:**
   - Set `spec.ingressClassName: nginx` (ours leaves it commented out — the controller may ignore an unclassed Ingress).
   - Provide the `baas-engine-tls` secret, **or** drop the `tls:` block to test plain HTTP.

### The actual test — the partner→card carve-out is the prize

```bash
curl -H "Host: api.nubbank.example.com" http://localhost/baas/v1/cards/...          # → baas-card
curl -H "Host: api.nubbank.example.com" http://localhost/baas/v1/customers/...      # → baas-engine (catch-all)
curl -H "Host: api.nubbank.example.com" http://localhost/baas/v1/card-products/...  # → baas-card
curl -H "Host: api.nubbank.example.com" http://localhost/open-banking/...           # → baas-ncube
```

That confirms longest-prefix routing sends the three card sub-paths to card and everything else under `/baas/v1` to
engine — the exact behavior added in Session 13.

### Gotcha

- The `Host:` header is mandatory — our Ingress is host-scoped to `api.nubbank.example.com`, so a bare
  `curl localhost/...` returns 404.

---

## Effort & combined rig

| Capability | Add-on | Setup cost | Catches |
|---|---|---|---|
| NetworkPolicy enforcement | Calico/Cilium + `disableDefaultCNI` cluster | medium | Wrong allow/deny rules, missing edges |
| FEP TCP LB | `cloud-provider-kind` **or** MetalLB | low–medium | Socket reachability, source-IP preservation |
| Ingress routing | ingress-nginx + port-mapped cluster + `ingressClassName` | low–medium | The card carve-out, host/prefix routing |

All three can run on **one** purpose-built kind cluster (`disableDefaultCNI` + Calico + ingress port-mappings +
`cloud-provider-kind`) — the realistic "full-fidelity" rig — but it is a meaningfully heavier build than the basic
smoke, and each add-on carries its own failure surface.

**Highest value:** the **NetworkPolicy negative-path test** is the one most likely to surface a real correctness gap
(e.g. a service that legitimately needs to reach another but has no allow rule). If only one of the three is run,
run that one.

---

## Prerequisites checklist (host tooling)

- `docker` (daemon running) — for kind nodes and image builds
- `kind` — cluster provisioning
- `kubectl` (bundled kustomize is sufficient)
- For §1: nothing extra beyond the Calico manifest
- For §2 Option A: `go` (to `go install cloud-provider-kind`)
- For §3: the kind ingress-nginx manifest (URL above)

> Note from the Session-13 smoke: building the project Dockerfiles in this environment hit a `bad_record_mac` TLS
> fault on the **Docker build VM's** path to Maven Central (the host network was fine). Workaround used: build JARs on
> the host (`./mvnw -DskipTests package`) and bake thin runtime images that `COPY target/app.jar`. The same workaround
> applies here. This is an environment quirk, not a Dockerfile/manifest problem.
