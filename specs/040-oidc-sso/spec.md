# Feature Specification: OIDC/SSO 登录与企业身份映射（first cut）

**Feature Branch**: `feat/040-oidc-sso`

**Created**: 2026-09-15

**Status**: First cut (thin stub — not full 九件套)

**Tracks**: #461（epic #454）

## Intent

Default-off OIDC authorization-code + PKCE login for the admin console. Maps IdP `(issuer, subject)` onto an existing local `web_users.username` via `identity_mappings`, then creates the existing `oryxos_session` cookie. Authorization continues to live behind session → Principal → `#462` / `AuthorizationService`; the OIDC callback **must not** call `AuthorizationService.decide` or compare roles.

## Hard constraints

- `oryxos.web.oidc.enabled` default **false** — zero behavior change when off
- No `spring-boot-starter-security` / `SecurityFilterChain`
- Callback/login path: authenticate + map + create `WebSession` only
- Auth audit table `auth_events` is append-only; LOGIN_SUCCESS uses fail-closed recorder (`recordOrThrow`)

## Out of scope (honest gaps)

- Default-off flags only: JIT user (`jit-provision-enabled`), group-roles / revoke-unmatched-roles, JIT team catalog (`jit-team-catalog-enabled` / #552), JIT team memberships (`jit-team-memberships-enabled` / #562), revoke unmatched memberships (`revoke-unmatched-team-memberships` / #564; empty groups → clear all when on). Organizations catalog + nullable `teams.org_id` landed (#554); `parent_org_id` + set-parent landed (#566); Admin org UI + team set-org landed (#556); Admin org set-parent UI landed (#570). Session `Principal.orgIds` cache from `teams.org_id` is opt-in (#560, `org-ids-from-team-org-enabled`). Still deferred: multilevel dept/project, OIDC group→org JIT.
- Admin UI for mapping
- Multi-IdP / discovery UI
- Full 九件套 research/plan/tasks/contracts

## Endpoints

- `GET /api/v1/auth/oidc/login` — 404 when disabled; else 302 to IdP authorize URL
- `GET /api/v1/auth/oidc/callback` — code+state → token → id_token → mapping → session; browser redirect `/admin/`, JSON when `Accept: application/json`

## Data

- `identity_mappings` — UNIQUE(issuer, subject) → username
- `auth_events` — LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / MAPPING_UPSERT / MAPPING_DELETE / GROUP_ROLE_SYNC
