# Provider

OryxOS 把 Spring AI Alibaba 用作薄薄的协议适配层——它处理各厂商的 wire format 差异（OpenAI、Anthropic、Gemini 等），OryxOS 本身不需要关心这些。在此之上，`ProviderService` 负责路由、审计，以及让多 Provider 可靠运行的显式名称到模型的映射。

## 支持的 Provider

| Provider 名称 | 厂商 | 备注 |
| --- | --- | --- |
| `deepseek` | DeepSeek | 大多数 Profile 的默认选择 |
| `qwen` | 阿里云 / 通义 | 通义系列模型 |
| `kimi` | Moonshot AI | 长上下文专项 |
| `zhipu` | 智谱 AI | GLM 系列 |
| `hunyuan` | 腾讯 | 混元系列 |
| `doubao` | 字节跳动 | 豆包 / Skylark 系列 |
| `anthropic` | Anthropic | Claude 系列 |
| `openai` | OpenAI | GPT 系列 |
| `ollama` | Ollama | 本地模型，无需 API key |

表中的 Provider 名称是 Profile YAML 和 `ProviderService` 内部映射表里使用的 key。这是一个任意字符串——映射关系是显式声明的，不是从类名或 Bean 类型推断的。

## 如何配置

每个 Provider 在 `application.yml`（或 `application-local.yml`）中声明，由 Spring AI Alibaba 的自动配置加载。API key 必须从环境变量注入，**不得**明文写在配置文件里。

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com
    alibaba:
      api-key: ${DASHSCOPE_API_KEY}   # 覆盖 qwen
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

## Provider 路由

每个 Profile 按名称声明恰好一个 Provider。`ProviderService` 在内部映射表里查找对应的 `ChatModel` 并交给 `ReActLoop`。

```yaml
# .oryxos/profiles/ops-agent.yaml
provider:
  name: deepseek          # 与 ProviderService 显式映射表里的 key 对应
  model: deepseek-chat
  temperature: 0.7
```

一个 OryxOS 实例可以同时运行使用不同 Provider 的 Agent。一个 Agent 用 `deepseek`，另一个用 `anthropic`——Profile 就是路由表条目。

## 为什么显式映射，而不是类型扫描

配置多个 Provider 时，Spring AI Alibaba 会注册多个 `ChatModel` Bean。问题在于：它们的类型都是 `ChatModel`，你无法通过检查 Bean 类型来区分 `deepseek` 和 `qwen`。

OryxOS 的解法是在启动时构建显式的 `Map<String, ChatModel>`：

```java
// 正确 — 以 provider name 为 key 的显式映射
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

// 错误 — 类型扫描无法区分各个 Provider
applicationContext.getBeansOfType(ChatModel.class); // 全都长一样
```

每个 Bean 通过 qualifier name 注入，而不是按类型注入。这就是为什么 Profile YAML 里的 Provider 名称必须与 Spring 配置里的 qualifier name 完全一致。

## 审计

每次 LLM 调用在返回给 `ReActLoop` 之前，都会向 SQLite 的 `llm_calls` 表写入一条记录，字段包括：`provider`、`model`、`prompt_tokens`、`completion_tokens`、`total_tokens`、`duration_ms`。token 数量来自 `ChatResponse.getMetadata().getUsage()`。

这是无条件执行的，没有配置开关可以关闭。

## 规划中的功能

以下内容不在核心阶段：

- **故障转移链** — 先试 Provider A，超时或报错时回退到 Provider B
- **竞速调用** — 同时向两个 Provider 发送请求，用先返回的那个
- **熔断器** — 自动停止向持续报错的 Provider 路由
- **基于成本的路由** — 自动把廉价查询路由到更便宜的模型
- **单次请求级 Provider 覆盖** — 单次 API 调用临时覆盖 Profile 配置的 Provider
