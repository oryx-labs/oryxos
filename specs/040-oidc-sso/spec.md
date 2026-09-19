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

- Default-off flags only: JIT user (`jit-provision-enabled`), group-roles / revoke-unmatched-roles, JIT team catalog (`jit-team-catalog-enabled` / #552), JIT team memberships (`jit-team-memberships-enabled` / #562), revoke unmatched memberships (`revoke-unmatched-team-memberships` / #564; empty groups → clear all when on). Organizations catalog + nullable `teams.org_id` landed (#554); `parent_org_id` + set-parent landed (#566); teams `parent_team_id` + set-parent landed (#581, catalog-only); Admin org UI + team set-org landed (#556); Admin org set-parent UI landed (#570); Admin org tree view landed (#575); Admin team tree view landed (#583); Admin team set-parent UI landed (#585). Session `Principal.orgIds` cache from `teams.org_id` is opt-in (#560, `org-ids-from-team-org-enabled`). Dedicated OIDC `org-ids-claim` → session `orgIds` landed (#590 / #587; default empty=off; opaque ids only; union with team-derived when both on). OIDC JIT org catalog ensure landed (#592, `jit-org-catalog-enabled`, default off; requires non-empty `org-ids-claim`). Admin identity_mappings list/CRUD landed (#577, `oryxos.web.oidc.mappings-api-enabled`). Admin HTML5 drag-drop reparent for org/team trees landed (#595; same-kind only; root zone clears parent; cycle guard stays server 400). Still deferred: multilevel dept/project beyond Admin trees, Multi-IdP. TeamOwner ancestor match landed (#588, default-off).
- Multi-IdP / discovery UI
- Full 九件套 research/plan/tasks/contracts

## Endpoints

- `GET /api/v1/auth/oidc/login` — 404 when disabled; else 302 to IdP authorize URL
- `GET /api/v1/auth/oidc/callback` — code+state → token → id_token → mapping → session; browser redirect `/admin/`, JSON when `Accept: application/json`
- `GET|POST|DELETE /api/v1/identity-mappings` — Admin CRUD; 404 unless `oryxos.web.oidc.mappings-api-enabled` (default false); DELETE uses `?issuer=&subject=`

## Data

- `identity_mappings` — UNIQUE(issuer, subject) → username
- `auth_events` — LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / MAPPING_UPSERT / MAPPING_DELETE / GROUP_ROLE_SYNC
