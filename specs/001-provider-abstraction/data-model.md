# Data Model: Provider（第16节）

## Profile（oryxos-core，YAML → Java 记录）

一个 Agent 的完整配置载体。本节建全字段、只校验/消费 provider 段；其余字段供后续各节取用。

| 字段 | 类型 | 说明 | 校验（本节） |
|---|---|---|---|
| name | String | 唯一标识 | 非空 |
| description | String | 描述 | — |
| identity | Identity(agentName, prompt) | 人格设定 | — |
| provider | ProviderRef(name, model, temperature) | 模型选择 | name 必须存在于全局 providers 清单；model 非空；temperature 可空（D6） |
| tools | List<String> | 可用工具名 | — |
| skills | List<String> | 引用的 SKILL.md | — |
| mcpServers | List<String> | 引用的 MCP server | — |
| channels | List<String> | 接入渠道 | — |
| notifyChannels | List<NotifyChannel(type, config:Map)> | 通知渠道（19 节用） | — |
| schedules | List<ScheduleConfig(id, cron, zone, message)> | 定时（25 节用） | — |
| bootstrap | List<String> | Bootstrap 文件 | — |
| settings | Settings(maxIterations=10, maxHistoryTurns=20) | 循环参数 | 缺省取默认值 |

**加载规则（ProfileLoader）**：扫 `.oryxos/profiles/*.yaml`；单文件解析/校验失败 → 记 ERROR、跳过、继续其余（SC-007）；YAML 字段名蛇形（`notify_channels`）映射驼峰。

**ProfileRegistry**：`Map<String, Profile>`（不可变视图对外），按 name 查找；本节仅启动扫描注册一条路径。

## ProvidersProperties（oryxos-provider，application.yaml 全局层）

```yaml
oryxos:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com          # OpenAI 兼容端点
    - name: kimi
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn/v1
```

| 字段 | 说明 | 校验 |
|---|---|---|
| name | provider 唯一名，映射表的 key | 非空、不重复 |
| api-key | `${ENV}` 占位，启动解析 | 解析后为空 → 启动报错点名哪个变量缺失 |
| base-url | OpenAI 兼容端点地址 | 非空 |

## LlmCall（oryxos-storage，JPA 实体 ↔ llm_calls 表）

| 列 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| session_id | VARCHAR NOT NULL | 会话关联 |
| provider | VARCHAR NOT NULL | provider 名 |
| model | VARCHAR NOT NULL | 模型名 |
| prompt_tokens | INTEGER | 输入 token（失败时可空） |
| completion_tokens | INTEGER | 输出 token（失败时可空） |
| total_tokens | INTEGER | 合计（失败时可空） |
| success | BOOLEAN NOT NULL | 成败标识 |
| error_message | TEXT | 失败原因（成功时空） |
| duration_ms | INTEGER NOT NULL | 耗时 |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | 记录时间 |

建表：`oryxos-storage/src/main/resources/schema.sql` 手工维护；测试与运行都执行此脚本（`ddl-auto=none`）。

## 状态与不变量

- Profile 加载后不可变（record）；Registry 只增不改（本节）。
- 每次 `chat` 调用 ↔ 恰好一条 LlmCall（成或败）；失败路径先写记录后抛异常（FR-005）。
