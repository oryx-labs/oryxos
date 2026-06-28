# Provider

OryxOS uses Spring AI Alibaba as a thin protocol adapter layer — it handles the per-vendor wire format differences (OpenAI, Anthropic, Gemini, etc.) so OryxOS does not have to. On top of that, `ProviderService` owns routing, auditing, and the explicit name-to-model mapping that makes multi-provider setups reliable.

## Supported providers

| Provider name | Vendor | Notes |
| --- | --- | --- |
| `deepseek` | DeepSeek | Default for most profiles |
| `qwen` | Alibaba Cloud / Tongyi | Qwen series models |
| `kimi` | Moonshot AI | Long-context specialist |
| `zhipu` | Zhipu AI | GLM series |
| `hunyuan` | Tencent | Hunyuan series |
| `doubao` | ByteDance | Doubao / Skylark series |
| `anthropic` | Anthropic | Claude series |
| `openai` | OpenAI | GPT series |
| `ollama` | Ollama | Local models, no API key needed |

The provider name in the table is the key used in Profile YAML and in `ProviderService`'s internal map. It is an arbitrary string — the mapping is explicit, not inferred from class names or Bean types.

## How to configure

Each provider is declared in `application.yml` (or `application-local.yml`) and picked up by Spring AI Alibaba's auto-configuration. API keys must come from environment variables — never hardcoded in config files.

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com
    alibaba:
      api-key: ${DASHSCOPE_API_KEY}   # covers qwen
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}

oryxos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      model: deepseek-chat
    kimi:
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn/v1
      model: moonshot-v1-8k
    ollama:
      base-url: http://localhost:11434
      model: llama3
```

## Provider routing

Each Profile declares exactly one provider by name. `ProviderService` looks up the corresponding `ChatModel` in its internal map and hands it to `ReActLoop`.

```yaml
# .oryxos/profiles/ops-agent.yaml
provider:
  name: deepseek          # matches the key in ProviderService's explicit map
  model: deepseek-chat
  temperature: 0.7
```

A single OryxOS instance can run agents on different providers simultaneously. One agent might use `deepseek`, another `anthropic` — the Profile is the routing table entry.

## Explicit mapping — why type scanning does not work

When multiple providers are configured, Spring AI Alibaba registers multiple `ChatModel` beans. The problem: they all have the same type (`ChatModel`). You cannot tell `deepseek` from `qwen` by inspecting the bean type.

OryxOS solves this with an explicit `Map<String, ChatModel>` built at startup:

```java
// Correct — explicit map keyed by provider name
@Bean
public Map<String, ChatModel> providerMap(
        ChatModel deepseekChatModel,
        ChatModel qwenChatModel,
        ChatModel kimiChatModel) {
    return Map.of(
        "deepseek", deepseekChatModel,
        "qwen",     qwenChatModel,
        "kimi",     kimiChatModel
    );
}

// Wrong — type scanning cannot distinguish providers
applicationContext.getBeansOfType(ChatModel.class); // all look the same
```

Each bean is injected by qualifier name, not by type. This is why provider names in Profile YAML must exactly match the qualifier names used in the Spring configuration.

## Auditing

Every LLM call writes one row to the `llm_calls` SQLite table before returning to `ReActLoop`. Fields recorded: `provider`, `model`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `duration_ms`. Token counts come from `ChatResponse.getMetadata().getUsage()`.

This happens unconditionally — there is no configuration flag to disable it.

## What is planned

The following are not in the core phase:

- **Fallback chains** — try provider A, fall back to provider B on timeout or error
- **Hedge racing** — send to two providers simultaneously, use the first response
- **Circuit breaker** — automatically stop routing to a provider that is returning errors
- **Cost-based routing** — route cheap queries to cheaper models automatically
- **Per-request provider override** — override the Profile's provider for a single API call
