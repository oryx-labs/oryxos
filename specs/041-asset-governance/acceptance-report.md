# Acceptance report — 041 asset governance first cut

**Status**: first cut on `feat/041-asset-governance` for #463.

## Met

- [x] Flag default off (`oryxos.web.asset-governance.enabled=false`)
- [x] `GOVERNANCE.yml` sidecar for agents / skills / knowledge
- [x] `AssetAwareAuthorizationServiceImpl` decorator reuses `AuthorizationService.decide` only
- [x] OFFLINE deny + PRIVATE owner/ADMIN gate (USER); API_KEY only OFFLINE
- [x] Bind/invoke extra decide via `AssetBindGuard`
- [x] GET/PUT governance APIs + V11 `asset_governance_events`
- [x] Unit tests: flag off passthrough, OFFLINE/PRIVATE, store roundtrip, guard

## Honest gaps

- No channel governance write/UI
- No JIT teams / org ownership
- No full version history
- Catalog visibility still label-only (no list filtering)
