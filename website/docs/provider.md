# Provider

OryxOS uses Spring AI Alibaba as a thin protocol adapter layer — it handles the per-vendor wire format differences (OpenAI, Anthropic, Gemini, etc.) so OryxOS does not have to. On top of that, `ProviderService` owns routing, auditing, and the explicit name-to-model mapping that makes multi-provider setups reliable.

## Providers are dynamic

Providers are no longer a fixed, startup-only map. They are **CRUD-able records stored in SQLite** (the `providers` table) and managed at runtime — through the `/api/v1/providers` REST endpoints or the admin console's **Provider** page. You can add, edit, and delete providers without restarting OryxOS.

A provider record has four fields:

| Field | Meaning |
| --- | --- |
| `name` | Global unique key. Agents reference it in their `AGENT.md`. |
| `apiKey` | Credential for the endpoint. Stored and returned in plaintext by design (see below). |
| `baseUrl` | OpenAI-compatible endpoint base URL. Required for every provider except `mock`. |
| `description` | Free-text note. |

The special name `mock` is a built-in fake model — it needs no `apiKey` and no `baseUrl`, and is used for key-free end-to-end testing. Every other (non-`mock`) provider **requires a base URL**.

## Startup seeding — config to database

On startup OryxOS reads the `oryxos.providers` list from `application.yml` and **seeds** each entry into the `providers` table **only if a provider with that name does not already exist**. After seeding, the **database is authoritative**: editing `oryxos.providers` in config will not overwrite a provider that is already in the DB. Manage live providers through the API or the admin console, not by editing config.

```yaml
# application.yml — seeds the DB on first startup only
oryxos:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}       # env placeholder, never hardcoded
      base-url: https://api.deepseek.com
    - name: kimi
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn/v1
    - name: mock                          # built-in fake model, no key/url needed
```

API keys come from environment-variable placeholders (`${...}`) in config — never hardcoded. Note that Spring **replaces** (does not merge) list-valued keys, so an external override of `oryxos.providers` replaces the whole list.

## Managing providers via REST

All endpoints live under `/api/v1` and wrap their payload in the unified `{ code, message, data, timestamp }` envelope (code `0` = success).

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/providers` | Create (`name`, `apiKey`, `baseUrl`, `description`); `400` on duplicate/blank name; non-`mock` requires `base-url` |
| `GET` | `/api/v1/providers` | List all providers |
| `GET` | `/api/v1/providers/{name}` | Get one (`404` if absent) |
| `PUT` | `/api/v1/providers/{name}` | Update (`apiKey`, `baseUrl`, `description`) |
| `DELETE` | `/api/v1/providers/{name}` | Delete |

The list and get responses (`ProviderView`) return `name`, `apiKey`, `baseUrl`, and `description`. The **`apiKey` is returned in plaintext** — a deliberate choice for the internal-network core phase, where the admin console needs to display and edit it. Do not expose these endpoints on an untrusted network.

## Provider routing

Each Agent's `AGENT.md` frontmatter declares exactly one provider by name. When `ReActLoop` needs an LLM call, `ProviderService` looks the provider up **by name in the registry** and resolves its `ChatModel`.

```yaml
# .oryxos/agents/ops-agent/AGENT.md (frontmatter)
provider:
  name: deepseek          # matches a provider record's name
  model: deepseek-chat
  temperature: 0.7
```

A single OryxOS instance can run agents on different providers simultaneously. One agent might use `deepseek`, another `anthropic` — the Agent's frontmatter is the routing table entry.

### Dynamic resolution and caching

The `ChatModel` for a provider is built on demand from its registry record and **cached by the key `name|apiKey|baseUrl`**. Because the cache key includes the credential and URL, editing a provider's key or base URL changes the key, so the model is **rebuilt on the next call** — a CRUD edit takes effect immediately, with no restart. The `mock` name resolves to the built-in fake model; everything else is built as an OpenAI-compatible client via `OpenAiApi(baseUrl, apiKey)`.

## Explicit mapping — why type scanning does not work

Constitution principle III still holds: routing is an **explicit name-to-`ChatModel` mapping**, never inferred from Spring bean types. The only change is that the map is now **runtime-mutable** — a `ProviderRegistry` backed by SQLite — instead of a static map frozen at startup.

The reason type scanning fails is unchanged. Every provider's model has the same Java type (`ChatModel` / `OpenAiChatModel`), so you cannot tell `deepseek` from `qwen` by inspecting the bean type:

```java
// Correct — resolve by name from the registry, build + cache per (name|apiKey|baseUrl)
ProviderDef def = registry.find(providerName).orElseThrow(...);
ChatModel model = resolveModel(def); // cached; rebuilt when key/url change

// Wrong — type scanning cannot distinguish providers
applicationContext.getBeansOfType(ChatModel.class); // all look the same
```

This is why the provider name in an Agent's frontmatter must exactly match a provider record's `name`.

## Auditing

Every LLM call writes one row to the `llm_calls` SQLite table — on both success and failure — before returning to `ReActLoop`. Fields recorded: `provider`, `model`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `duration_ms`. Token counts come from `ChatResponse.getMetadata().getUsage()`.

This happens unconditionally — there is no configuration flag to disable it.

## What is planned

The following are not in the core phase:

- **Fallback chains** — try provider A, fall back to provider B on timeout or error
- **Hedge racing** — send to two providers simultaneously, use the first response
- **Circuit breaker** — automatically stop routing to a provider that is returning errors
- **Cost-based routing** — route cheap queries to cheaper models automatically
- **Per-request provider override** — override the Agent's provider for a single API call
