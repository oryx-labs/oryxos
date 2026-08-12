# Authentication

OryxOS ships with an **opt-in** HTTP Basic Auth for the management console (`/admin/**`). It is disabled by default — the core phase assumes a trusted internal network. When enabled, access to the admin console requires an account; the REST API (`/api/v1/**`) is **not** affected.

> Basic Auth is suited to internal networks fronted by HTTPS. For internet-facing deployments, terminate TLS at a reverse proxy and restrict network exposure; this minimal auth is a first line, not a perimeter.

## How it works

- **Scope**: only `/admin/**` is protected. `/api/v1/**` (REST endpoints, including `/api/v1/health`) stays open — machine-to-machine API auth (API Key) is a separate, future feature.
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

## Design notes

- **No Spring Security full stack**: only `spring-security-crypto` (the password-hashing jar) is used — no filter chain, no autoconfig, no RBAC. The `BasicAuthFilter` is a plain `OncePerRequestFilter` registered via a `FilterRegistrationBean` scoped to `/admin/**`.
- **What this is not**: this is not SSO, RBAC, multi-tenancy, or session-based login with logout. Those are extension-phase capabilities. Password hashing with a delegating encoder leaves an upgrade path to Argon2 without migration.
- **HTTP Basic has no logout** — clearing credentials is browser-controlled. For richer session semantics, a future feature can add a login page backed by the same `web_users` table.
