<!--
  NubBank BaaS — Pull Request

  Delete sections that don't apply. Don't delete the headings — reviewers grep
  for them.

  Expert Review is opt-in. If you want a 20+ yr core-banking sanity check on
  this PR, type `expert review` in the PR conversation and the bot/assistant
  will produce one. It is not required.
-->

## Summary

<!-- 1–3 sentences. What changed and why. Focus on the why. -->

## Scope

<!-- Which areas of the codebase does this touch? Helpful for reviewers. -->

- [ ] `baas-engine/` — Java services
- [ ] `baas-ncube/` — CBN/Ncube adapter
- [ ] `baas-portal/` / `baas-backoffice/` — React UIs
- [ ] `infrastructure/` — Docker / Kubernetes / CI
- [ ] `docs/` — documentation only

## Test plan

- [ ] Unit tests for the change
- [ ] Integration tests (Testcontainers) for any multi-tenancy / schema / transaction-boundary change
- [ ] Manual verification (link the trace ID, screenshot, or curl output)

## Risks / rollback

<!-- What's the worst that could happen? What's the rollback if it fires in prod? -->

## Links

- Plan / spec:
- Related issue:
- Related session(s) in `baas-log.md`:
