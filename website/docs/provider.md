# Provider

OryxOS supports multiple LLM providers through an explicit provider mapping — no vendor lock-in, switch providers by changing a single YAML field.

## Supported Providers

| Provider | Models |
|----------|--------|
| DeepSeek | `deepseek-chat`, `deepseek-reasoner` |
| Qwen (Alibaba Cloud) | `qwen-max`, `qwen-plus`, `qwen-turbo` |
| Kimi (Moonshot) | `moonshot-v1-8k`, `moonshot-v1-32k` |
| OpenAI | `gpt-4o`, `gpt-4o-mini`, `o1-mini` |
| Ollama (local) | `qwen2.5:7b`, `llama3.2`, any local model |

## Configuration

In your Profile YAML, configure the provider block:

```yaml
provider:
  name: deepseek        # Maps to the explicit provider registry
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
  temperature: 0.7
```

Switch to a local model with no code changes:

```yaml
provider:
  name: ollama
  model: qwen2.5:7b
  base_url: http://localhost:11434
```

## Architecture

OryxOS maintains an explicit `Map<String, ChatModel>` provider registry. This avoids the common pitfall of scanning the Spring container by bean type, which breaks when multiple providers share the same bean class.

Spring AI is used only for protocol translation (OpenAI / Anthropic / Gemini wire format differences) and `@Tool` JSON Schema generation. The ReAct Loop and tool execution are fully self-implemented.
