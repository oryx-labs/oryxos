# Authentication

OryxOS ships with an **opt-in** HTTP Basic Auth for the management console (`/admin/**`). It is disabled by default — the core phase assumes a trusted internal network. When enabled, access to the admin console requires an account; the REST API (`/api/v1/**`) is **not** affected.

> Basic Auth is suited to internal networks fronted by HTTPS. For internet-facing deployments, terminate TLS at a reverse proxy and restrict network exposure; this minimal auth is a first line, not a perimeter.

## How it works

- **Scope**: only `/admin/**` is protected. Machine-to-machine auth for `/api/v1/**` is handled by the separate [REST API Key](#rest-api-key-authentication) switch — the two toggles are independent.
- **Accounts**: stored in the `web_users` SQLite table. Passwords are BCrypt-hashed (with a `{bcrypt}` prefix via Spring's `DelegatingPasswordEncoder`) — **never stored in plaintext**, never written to config, logs, or git history.
- **Realtime**: account changes take effect immediately. Each request re-reads the database — no in-process cache, no server restart needed for a new account to work.
- **Startup guard**: if you enable auth but no enabled account exists, startup is blocked with a clear error pointing at `oryxos user add`.

## Configuration

Auth is controlled under `oryxos.web.auth` in `application.yml` (the in-jar defaults, overridable via `config/application.yml`):

```yaml
oryxos:
  web:
    auth:
      enabled: false        # default off — trusted internal network
      realm: "OryxOS"        # Basic Auth realm string in the WWW-Authenticate header
```

| Property | Default | Description |
| --- | --- | --- |
| `oryxos.web.auth.enabled` | `false` | Master switch. `false` = no auth (current behavior). `true` = `/admin/**` requires Basic Auth. |
| `oryxos.web.auth.realm` | `OryxOS` | Realm value sent back in the `WWW-Authenticate: Basic realm="..."` challenge. |

There is no `exclude-paths` setting — the filter is scoped to `/admin/**` only, so `/api/v1/**` (and `/api/v1/health`) is naturally exempt.

## Quick start

1. **Enable auth** in `config/application.yml`:

   ```yaml
   oryxos:
     web:
       auth:
         enabled: true
   ```

2. **Create the first admin account** (startup is blocked until at least one enabled account exists):

   ```bash
   oryxos user add admin
   # Password (>= 8 chars): ********
   # Confirm: ********
   # Created user 'admin'
   ```

3. **Start the server**:

   ```bash
   oryxos serve
   ```

4. **Open the console** at `http://localhost:8080/admin/` — the browser prompts for credentials. Enter the account you just created.

## Verifying it works

```bash
# No credentials → 401 + WWW-Authenticate challenge
curl -i http://localhost:8080/admin/

# Correct credentials → 200
curl -u admin:<password> http://localhost:8080/admin/

# REST API stays open (not protected by Basic Auth)
curl http://localhost:8080/api/v1/health
```

## Account management

See the [`oryxos user` CLI reference](./cli.md#user-management) for `add`, `list`, `passwd`, `disable`, and `delete`.

- `list` **never prints passwords or hashes**.
- `disable` keeps the row but blocks login (returns 401). `delete` removes it permanently.
- Passwords must be ≥ 8 characters; usernames must be ≤ 64 characters with no whitespace.

## REST API Key authentication

Machine-to-machine auth for `/api/v1/**` (018-rest-api-key), toggled independently from console auth above:

```yaml
oryxos:
  web:
    apikey:
      enabled: true   # default false — current behavior unchanged
```

- **Before enabling**, create a key with `oryxos apikey add <name>` — the `oryx_...` plaintext is **shown exactly once**; only its SHA-256 hash is stored.
- **Callers** pick either header: `Authorization: Bearer <key>` or `X-API-Key: <key>` — both are equivalent.
- **Exemptions**: `/api/v1/health` (probes), `/api/v1/auth/*` (console login subtree), and OPTIONS preflight; `/admin/**` is entirely unaffected.
- **Console interop**: requests carrying a valid console session pass as authenticated — with both switches on, admin data pages keep working. Enabling only apikey logs a startup warning (the browser has neither session nor key).
- **Lifecycle**: `oryxos apikey list` for inventory (no plaintext); `oryxos apikey revoke <name>` takes effect on the next request and leaves other keys untouched. Keys never expire automatically; a lost key can only be revoked and reissued.

```bash
# No key → 401 (uniform response, no failure-reason leak)
curl -i http://localhost:8080/api/v1/profiles
# With key → 200
curl -H "Authorization: Bearer oryx_..." http://localhost:8080/api/v1/profiles
# Probes stay open
curl http://localhost:8080/api/v1/health
```

## Enterprise OIDC/SSO login

The admin console supports standard **OIDC authorization code + PKCE** login (040-oidc-sso) — accounts from your existing IdP (Keycloak / Azure AD / Authing / anything with OIDC Discovery) sign in directly, and the external identity is mapped to an OryxOS user and roles:

```yaml
oryxos:
  web:
    oidc:
      enabled: true                              # default false — zero change when off
      issuer: https://idp.example.com/realms/main
      client-id: oryxos-admin
      client-secret: ${OIDC_CLIENT_SECRET}       # inject via env var, never plaintext
      redirect-base-url: https://oryxos.example.com
      roles-claim: groups                        # optional: claim → role mapping
      role-mappings:
        "/oryxos-admins": ADMIN
```

- **Local password login always coexists**: the login page shows both entries, so an IdP outage never locks out admins; enabling OIDC requires `auth.enabled: true`.
- **Identity mapping**: anchored on `iss+sub` (renames and email changes don't affect it); unknown users are provisioned just-in-time on first login with the lowest default role (VIEWER).
- **Conditional role authority**: with `role-mappings` configured and a claim hit, roles refresh on every login (removing a group demotes immediately); with no mapping or no hit, local roles are kept (`oryxos user role` stays effective).
- **Audit**: login success/failure (categorized), logout, mapping creation, and role changes all land in the `auth_events` table; token contents never touch the database or logs.
- Register the IdP callback as `{redirect-base-url}/api/v1/auth/oidc/callback`. Full details in `docs/OidcSsoGuide.md`.

## Design notes

- **No Spring Security full stack**: only `spring-security-crypto` (the password-hashing jar) and `nimbus-jose-jwt` (the ID Token verification jar) are used — no filter chain, no autoconfig. The `BasicAuthFilter` is a plain `OncePerRequestFilter` registered via a `FilterRegistrationBean` scoped to `/admin/**`; the OIDC flow lives in the same self-implemented filter/controller stack.
- **What this is not**: no multi-tenancy, no SCIM user sync, no back-channel logout, no multiple IdPs. SSO (040) and RBAC (039) are now built in; password hashing with a delegating encoder leaves an upgrade path to Argon2 without migration.
- **HTTP Basic has no logout** — clearing credentials is browser-controlled. For richer session semantics, a future feature can add a login page backed by the same `web_users` table.
