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
- [x] Inbound OFFLINE gate (`InboundAssetGovernanceGate` in `InboundMessageService`; challenges stay in adapters)
- [x] Admin UI: Agent / Skill / Knowledge detail「治理」panels → `GET/PUT /api/v1/{agents|skills|knowledge}/{name}/governance`
- [x] Admin UI: 入站渠道 list + governance panel → `GET/PUT /api/v1/channels/{name}/governance`
- [x] Catalog list filter via `AssetBindGuard.isVisible` → `decide(READ_WORKSPACE, named resource)` on agents/skills/knowledge/channels lists
- [x] WORKSPACE `teamOwner` gate behind `workspace-team-acl-enabled` (default off); OIDC groups → session `Principal.teamIds`
- [x] WORKSPACE `teamOwner` ancestor match behind `workspace-team-acl-ancestor-enabled` (default off; #588; bounded `parent_team_id` walk; depth reuses `max-org-ancestor-depth`)
- [x] WORKSPACE `orgOwner` gate behind `workspace-org-acl-enabled` (default off); teamIds × teams.org_id (#558)
- [x] WORKSPACE `orgOwner` ancestor match behind `workspace-org-acl-ancestor-enabled` (default off; #568; bounded `parent_org_id` walk)
- [x] `setParent` rejects org parent cycles via bounded walk (depth shared with #568; default 16; configurable via `max-org-ancestor-depth` #579; #573)
- [x] `/skills/catalog` installed rows also filtered via `AssetBindGuard.isVisible` (012 tags unchanged for external candidates)
- [x] Agent author paths (`validateCatalog` / `generate-files` / `saveFiles` skill bindings) honor `isVisible` predicate
- [x] Knowledge author paths (create/bind/replace/saveFiles/generate-files) honor `isVisible` predicate
- [x] Password login optional `oryxos.web.auth.user-team-ids` → `SessionTeamIdsCache` (default empty)

- [x] Admin teams page (#548) behind same `teams-api.enabled` (list/create/rename/delete + user membership add/remove)
- [x] Admin organizations page (#556/#570) on same teams Admin surface (org list/create/rename/delete + parentOrgId show/set/clear + team set-org/clear) behind same `teams-api.enabled`
- [x] Admin revision history UI (#550): Agent / Skill / Knowledge / Channel governance panels → list / diff / restore (`version-history-enabled`)

- [x] Team catalog `parent_team_id` + set-parent behind `teams-api.enabled` (#581; bounded cycle guard; SQLite clears children on delete)
- [x] Admin teams tree view (#583): client indent by `parentTeamId` on teams Admin surface (same `teams-api.enabled`)
- [x] Admin teams set-parent UI (#585): set/clear `parentTeamId` via `PUT /api/v1/teams/{id}/parent` (mirrors org #570)
- [x] Admin HTML5 drag-drop reparent (#595): org/team tree rows + root clear zone → existing set-parent APIs (no cross org↔team; no sibling reorder)

## Honest gaps

- Organizations catalog + nullable `teams.org_id` done (#554); `parent_org_id` + set-parent done (#566); setParent bounded cycle guard done (#573); org ancestor decide opt-in done (#568); team ancestor decide opt-in done (#588); configurable `max-org-ancestor-depth` done (#579, default 16; reused by team); Admin org UI + team set-org done (#556); Admin org set-parent UI done (#570); Admin tree done (#575); teams `parent_team_id` + set-parent done (#581); Admin team tree done (#583); Admin team set-parent UI done (#585); Admin HTML5 drag-drop reparent for org/team trees done (#595); WORKSPACE orgOwner gate done (#558); OIDC org-ids claim → session orgIds done (#590); OIDC JIT org catalog ensure done (#592, default-off); Multi-IdP still deferred
- OIDC JIT team catalog ensure done behind `oryxos.web.oidc.jit-team-catalog-enabled` (#552); OIDC JIT org catalog behind `jit-org-catalog-enabled` (#592, requires non-empty `org-ids-claim`); OIDC JIT durable `team_memberships` add done behind `oryxos.web.oidc.jit-team-memberships-enabled` (#562, default off; skip if no catalog row); revoke unmatched behind `revoke-unmatched-team-memberships` (#564, default off; empty groups clears all)
- Team memberships may reference ids without a catalog row (catalog is optional metadata; JIT catalog flag optionally fills rows from IdP groups; JIT memberships flag skips when catalog row missing)
