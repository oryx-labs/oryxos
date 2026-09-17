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

- JIT / auto-provision local users from IdP claims
- Groups → roles is config-only (`oryxos.web.oidc.group-roles`). Empty map does not touch roles. Unmatched login does not revoke. No admin UI for the map. No JIT.
- Admin UI for mapping
- Multi-IdP / discovery UI
- Full 九件套 research/plan/tasks/contracts

## Endpoints

- `GET /api/v1/auth/oidc/login` — 404 when disabled; else 302 to IdP authorize URL
- `GET /api/v1/auth/oidc/callback` — code+state → token → id_token → mapping → session; browser redirect `/admin/`, JSON when `Accept: application/json`

## Data

- `identity_mappings` — UNIQUE(issuer, subject) → username
- `auth_events` — LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT / MAPPING_UPSERT / MAPPING_DELETE / GROUP_ROLE_SYNC
