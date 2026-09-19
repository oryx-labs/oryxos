# Acceptance report — 040 OIDC/SSO first cut

**Status**: first cut landed on `feat/040-oidc-sso` for #461.

## Met

- [x] Flag default off (`oryxos.web.oidc.enabled=false`)
- [x] Auth-code + PKCE login/callback under `/api/v1/auth/oidc/**`
- [x] `identity_mappings` + `auth_events` (V10)
- [x] Session cookie reuses `oryxos_session` (same as password login)
- [x] Callback does not call `AuthorizationService`
- [x] Unit tests: disabled→404, state mismatch, unmapped, mapped→session, no authorize decide

## Honest gaps

- JIT user provision / revoke-unmatched-roles / group-roles / JIT team catalog / JIT team memberships / revoke-unmatched-team-memberships are default-off flags (#502/#552/#562/#564); orgs table + `teams.org_id` done (#554); `parent_org_id` + set-parent done (#566); teams `parent_team_id` + set-parent done (#581, catalog-only); Admin org UI + team set-org done (#556); Admin org set-parent UI done (#570); Admin team tree done (#583); Admin team set-parent UI done (#585); Admin HTML5 drag-drop reparent for org/team trees done (#595); OIDC org-ids claim → session orgIds done (#590); OIDC JIT org catalog ensure done (#592, default-off); Multi-IdP / broader org-binding still deferred; teamOwner ancestor decide opt-in done (#588)
- Mapping admin UI landed (#577/#578); CLI `oryxos user oidc-map` still available
- Thin spec (not full 九件套)
- Single IdP configuration only
- JWKS/id_token verify behind `OidcTokenClient` (real HTTP+Nimbus impl; tests inject fake)
