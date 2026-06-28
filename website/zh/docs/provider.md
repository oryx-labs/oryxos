# Provider 路由

OryxOS 通过显式 Provider 映射支持多个 LLM Provider——无供应商锁定，修改一行 YAML 即可切换 Provider。

## 支持的 Provider

| Provider | 模型 |
|----------|------|
| DeepSeek | `deepseek-chat`、`deepseek-reasoner` |
| Qwen（阿里云） | `qwen-max`、`qwen-plus`、`qwen-turbo` |
| Kimi（月之暗面） | `moonshot-v1-8k`、`moonshot-v1-32k` |
| OpenAI | `gpt-4o`、`gpt-4o-mini`、`o1-mini` |
| Ollama（本地） | `qwen2.5:7b`、`llama3.2`，任意本地模型 |

## 配置方式

在 Profile YAML 中配置 provider 块：

```yaml
provider:
  name: deepseek        # 对应 ProviderService 里的显式映射 key
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
  temperature: 0.7
```

切换到本地模型，无需修改代码：

```yaml
provider:
  name: ollama
  model: qwen2.5:7b
  base_url: http://localhost:11434
```

## 架构原则

OryxOS 维护一个显式的 `Map<String, ChatModel>` Provider 注册表。这避免了通过 Spring 容器扫描 Bean 类型来区分 Provider 的常见陷阱——当多个 Provider 共享同一 Bean 类型时该方式会失效。

Spring AI 只用于协议转换（OpenAI / Anthropic / Gemini 格式差异）和 `@Tool` JSON Schema 生成。ReAct Loop 和工具调用完全自实现。
