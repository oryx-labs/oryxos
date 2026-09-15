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

- No JIT user creation from IdP
- No groups→roles bridge
- No mapping admin UI (CLI `oryxos user oidc-map` only)
- Thin spec (not full 九件套)
- Single IdP configuration only
- JWKS/id_token verify behind `OidcTokenClient` (real HTTP+Nimbus impl; tests inject fake)
