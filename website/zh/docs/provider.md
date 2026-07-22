# Provider

OryxOS 把 Spring AI Alibaba 用作薄薄的协议适配层——它处理各厂商的 wire format 差异（OpenAI、Anthropic、Gemini 等），OryxOS 本身不需要关心这些。在此之上，`ProviderService` 负责路由、审计，以及让多 Provider 可靠运行的显式名称到模型的映射。

## Provider 是动态的

Provider 不再是启动时固定不变的映射表。它现在是**存储在 SQLite（`providers` 表）里、可运行时增删改的记录**，通过 `/api/v1/providers` REST 端点或管理台的 **Provider** 页面管理。无需重启 OryxOS 即可新增、编辑、删除 Provider。

一条 Provider 记录有四个字段：

| 字段 | 含义 |
| --- | --- |
| `name` | 全局唯一 key，Agent 在自己的 `AGENT.md` 里按它引用 |
| `apiKey` | 端点凭证。按设计以明文存储、明文返回（见下文） |
| `baseUrl` | OpenAI 兼容端点的 base URL。除 `mock` 外每个 Provider 都必填 |
| `description` | 自由文本备注 |

保留名 `mock` 是内置的假模型——不需要 `apiKey` 也不需要 `baseUrl`，用于无 key 的全链路自测。其余（非 `mock`）Provider **都必须填 base URL**。

## 启动播种——从配置写入数据库

启动时 OryxOS 读取 `application.yml` 里的 `oryxos.providers` 列表，并把每一条**播种**进 `providers` 表——**仅当同名 Provider 尚不存在时**才写入。播种之后，**数据库是唯一真相源**：修改配置里的 `oryxos.providers` 不会覆盖已经在 DB 里的 Provider。请通过 API 或管理台管理线上 Provider，而不是改配置。

```yaml
# application.yml —— 仅在首次启动时播种进 DB
oryxos:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}       # 环境变量占位符，不明文写死
      base-url: https://api.deepseek.com
    - name: kimi
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn/v1
    - name: mock                          # 内置假模型，无需 key/url
```

API key 来自配置里的环境变量占位符（`${...}`）——不得明文写死。注意 Spring 对列表型配置是**整体替换**而非合并，所以外部覆盖 `oryxos.providers` 会替换掉整个列表。

## 通过 REST 管理 Provider

所有端点都在 `/api/v1` 前缀下，返回体统一包裹在 `{ code, message, data, timestamp }` 信封里（`code` 为 `0` 表示成功）。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/v1/providers` | 新建（`name`、`apiKey`、`baseUrl`、`description`）；重名/空名返回 `400`；非 `mock` 必须填 `base-url` |
| `GET` | `/api/v1/providers` | 列出全部 Provider |
| `GET` | `/api/v1/providers/{name}` | 查询单个（不存在返回 `404`） |
| `PUT` | `/api/v1/providers/{name}` | 更新（`apiKey`、`baseUrl`、`description`） |
| `DELETE` | `/api/v1/providers/{name}` | 删除 |

列表和单查响应（`ProviderView`）返回 `name`、`apiKey`、`baseUrl`、`description`。其中 **`apiKey` 以明文返回**——这是内网核心阶段的有意选择，管理台需要展示和编辑它。不要把这些端点暴露到不可信网络。

## Provider 路由

每个 Agent 的 `AGENT.md` frontmatter 按名称声明恰好一个 Provider。当 `ReActLoop` 需要调用 LLM 时，`ProviderService` **按名称在注册表里查找**该 Provider 并解析出它的 `ChatModel`。

```yaml
# .oryxos/agents/ops-agent/AGENT.md（frontmatter）
provider:
  name: deepseek          # 与某条 Provider 记录的 name 对应
  model: deepseek-chat
  temperature: 0.7
```

一个 OryxOS 实例可以同时运行使用不同 Provider 的 Agent。一个 Agent 用 `deepseek`，另一个用 `anthropic`——Agent 的 frontmatter 就是路由表条目。

### 动态解析与缓存

Provider 的 `ChatModel` 按需从注册表记录构建，并以 **`name|apiKey|baseUrl` 为 key 缓存**。因为缓存 key 包含了凭证和 URL，改动某个 Provider 的 key 或 base URL 就会换掉缓存 key，于是模型在**下次调用时重建**——一次 CRUD 编辑立即生效，无需重启。`mock` 名解析到内置假模型；其余都通过 `OpenAiApi(baseUrl, apiKey)` 构建成 OpenAI 兼容客户端。

## 为什么显式映射，而不是类型扫描

宪法原则三依然成立：路由是**显式的名称到 `ChatModel` 映射**，绝不从 Spring Bean 类型推断。唯一的变化是这张映射表现在是**运行时可变的**——由 SQLite 支撑的 `ProviderRegistry`——而不是启动时冻结的静态 map。

类型扫描失败的原因没变。每个 Provider 的模型 Java 类型都相同（`ChatModel` / `OpenAiChatModel`），你无法通过检查 Bean 类型区分 `deepseek` 和 `qwen`：

```java
// 正确 —— 按名从注册表解析，按 (name|apiKey|baseUrl) 构建 + 缓存
ProviderDef def = registry.find(providerName).orElseThrow(...);
ChatModel model = resolveModel(def); // 缓存；key/url 变化时重建

// 错误 —— 类型扫描无法区分各个 Provider
applicationContext.getBeansOfType(ChatModel.class); // 全都长一样
```

这就是为什么 Agent frontmatter 里的 Provider 名称必须与某条 Provider 记录的 `name` 完全一致。

## 审计

每次 LLM 调用——无论成功还是失败——在返回给 `ReActLoop` 之前都会向 SQLite 的 `llm_calls` 表写入一条记录，字段包括：`provider`、`model`、`prompt_tokens`、`completion_tokens`、`total_tokens`、`duration_ms`。token 数量来自 `ChatResponse.getMetadata().getUsage()`。

这是无条件执行的，没有配置开关可以关闭。

## 规划中的功能

以下内容不在核心阶段：

- **故障转移链** — 先试 Provider A，超时或报错时回退到 Provider B
- **竞速调用** — 同时向两个 Provider 发送请求，用先返回的那个
- **熔断器** — 自动停止向持续报错的 Provider 路由
- **基于成本的路由** — 自动把廉价查询路由到更便宜的模型
- **单次请求级 Provider 覆盖** — 单次 API 调用临时覆盖 Agent 配置的 Provider
