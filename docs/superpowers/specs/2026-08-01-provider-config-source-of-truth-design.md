# Provider 配置单一事实源修复设计

## 背景

Issue [#42](https://github.com/oryx-labs/oryxos/issues/42) 指出 Provider 配置存在双事实源：

- `config/application.yml` 中的 `oryxos.providers`；
- SQLite `providers` 表中的运行时注册表。

当前 `OryxOsRuntime.providerRegistry()` 每次启动都会把 YAML 配置无条件
`save()` 到注册表。由于 `save()` 是按 Provider 名称 upsert，管理台已经修改的
API key 或 base URL 会在重启时被旧 YAML 覆盖；当环境变量未设置、YAML 中的
API key 解析为空字符串时，还会把数据库中的有效 key 清空。

这与现有契约“YAML 只在数据库没有对应 Provider 时做首次播种，之后以数据库
为准”不一致，也破坏了动态 Provider 管理的持久化语义。

## 已确认的产品语义

1. SQLite `ProviderRegistry` 是唯一运行时事实源。
2. YAML 只负责首次播种数据库中尚不存在的 Provider。
3. 数据库已有有效 Provider 时，即使当前环境没有设置对应 API key，
   `serve`、`gateway` 和 `chat` 仍使用数据库配置正常启动。
4. 空白或未解析的 YAML API key 不得写入数据库。
5. 历史版本已经写入数据库的无效记录不自动修复或覆盖；需要在启动时清晰报错。
6. 不调用模型的轻命令不应被 Provider 可用性校验阻断。

## 目标

- 防止启动过程覆盖数据库中已有的 Provider。
- 防止空白或未解析的 YAML API key 被持久化。
- 让所有需要 LLM 的启动入口校验最终生效的注册表，而不是原始 YAML。
- 保留 Provider 显式名称映射、动态 CRUD 和现有 REST 契约。
- 用真正的跨 Spring Context、共享 SQLite 文件测试证明重启后配置不回滚。

## 非目标

- 不迁移或自动修复历史无效数据库记录。
- 不改变 `providers` 表结构。
- 不改变 Provider REST API、Agent 配置格式或模型路由协议。
- 不实现密钥加密、外部 Secret Manager 或凭证轮换。
- 不修改 Provider API 的创建和更新校验规则；本修复只处理启动播种与运行前校验。
- 不把 YAML 和数据库做字段级合并。

## 方案比较

### 方案一：只给现有 `save()` 增加存在性判断

在 `OryxOsRuntime` 中仅当 `registry.exists(name)` 为 false 时调用 `save()`。

优点是改动最小。缺点是 `ProviderStartupCheck` 仍校验原始 YAML，因此数据库
已有有效 Provider、YAML key 为空时，Web 启动依旧失败，不能满足已确认语义。

### 方案二：首次播种并校验最终注册表

把首次播种和运行前校验分成独立职责：

- 播种器只写入数据库中不存在且 YAML 配置完整的 Provider；
- 校验器只校验播种完成后的 `ProviderRegistry`；
- `serve`、`gateway`、`chat` 复用同一个校验器。

该方案完整解决 Issue，保持数据库单一事实源，且能独立测试。采用此方案。

### 方案三：YAML 与数据库按字段合并

数据库缺字段时用 YAML 补齐，YAML 有新值时按规则更新数据库。

该方案看似灵活，但会继续保留双事实源，需要定义复杂的字段优先级和覆盖规则，
容易再次产生静默数据回滚，因此不采用。

## 架构与职责

### `ProviderRegistryBootstrap`

放在 `oryxos-provider` 模块，负责启动播种，不负责判断应用是否可以提供 LLM
能力。

输入：

- `ProviderRegistry`；
- `ProvidersProperties`。

行为：

1. 遍历 YAML Provider。
2. 同名记录已存在时直接跳过，不调用 `save()`。
3. 同名记录不存在且配置可作为种子时调用 `save()`。
4. 普通 Provider 的 seed 必须包含非空名称、非空且已解析的 API key、非空
   base URL。
5. `mock` Provider 允许 API key 和 base URL 为空。
6. 无效 YAML seed 不写数据库，记录不包含凭证值的告警。

### `ProviderRegistryValidator`

放在 `oryxos-provider` 模块，负责校验最终注册表是否可供 LLM 入口使用。

规则：

- 注册表必须至少包含一个 Provider。
- Provider 名称必须非空且唯一；注册表以名称为主键，重复属于防御性检查。
- `mock` Provider 不要求 API key 和 base URL。
- 普通 Provider 必须有非空 API key 和 base URL。
- 错误消息只点名 Provider 和缺失字段，不回显任何凭证内容。

### `OryxOsRuntime`

`providerRegistry()` 继续创建 `JpaProviderRegistry`，随后调用
`ProviderRegistryBootstrap.seedMissing()`。删除当前内联的无条件 upsert。

Bean 创建阶段只播种，不做“必须存在可用 Provider”的严格校验，以保证
`user` 等不使用 LLM 的非 Web 命令仍能启动。

### `ProviderStartupCheck`

继续只在 Servlet Web 应用中生效，但改为注入 `ProviderRegistry` 和
`ProviderRegistryValidator`，校验最终注册表。校验仍发生在 Web Server
开始监听端口之前。

因此 `serve` 和当前以 Servlet 模式启动的 `gateway` 都使用数据库最终状态，
不再受空 YAML key 影响。

### `ChatCommand`

`chat` 使用 `WebApplicationType.NONE`，不会触发 `ProviderStartupCheck`。
它在 Spring Context 创建后、进入 `CliChannel` 之前，显式调用同一个
`ProviderRegistryValidator`。

校验失败时不进入交互循环，并向用户返回点名 Provider 的配置错误。

## 数据流

```text
启动
  -> 创建 JpaProviderRegistry
  -> ProviderRegistryBootstrap 遍历 YAML
       -> DB 已有同名记录：跳过
       -> DB 无记录 + YAML 有效：首次写入
       -> DB 无记录 + YAML 无效：告警并跳过
  -> 得到最终 ProviderRegistry
       -> serve/gateway：ProviderStartupCheck 校验
       -> chat：ChatCommand 显式校验
       -> user 等轻命令：不校验
  -> LLM 调用始终按名称从 ProviderRegistry 读取配置
```

## 决策表

| 数据库状态 | YAML 状态 | 播种结果 | LLM 入口结果 |
|---|---|---|---|
| 无同名记录 | 有效 | 首次写入 | 校验通过 |
| 无同名记录 | key 为空或未解析 | 不写入 | 无其他可用 Provider 时清晰失败 |
| 已有有效同名记录 | 有效但值不同 | 保留数据库值 | 校验通过 |
| 已有有效同名记录 | key 为空 | 保留数据库值 | 校验通过 |
| 已有无效同名记录 | 任意 | 保留数据库值，不自动修复 | 清晰失败 |
| `mock` 不存在 | 无 key/base URL 的有效 mock 定义 | 首次写入 | 校验通过 |

## 错误处理与安全

- 日志和异常不得包含 API key、请求头或其他凭证值。
- 无效 YAML seed 使用告警说明“未播种哪个 Provider 及缺少什么”，但不把
  原始值写入日志。
- 无效数据库记录由 Validator 阻止 LLM 入口继续启动，避免把错误推迟到第一
  次模型调用才暴露。
- 本修复不删除、不覆盖历史记录，避免以“自动修复”为名造成第二次数据损坏。
- 数据库读写异常保持现有显式失败行为，不静默降级为 YAML。

## 测试策略

### 单元测试

`ProviderRegistryBootstrapTest`：

1. 数据库无记录、YAML 有效时写入一次。
2. 数据库已有同名 Provider 时不调用 `save()`。
3. 数据库已有 Provider、YAML 值不同时保留数据库值。
4. 数据库无记录、YAML key 为空或未解析时不写入。
5. `mock` Provider 可在无 key/base URL 时播种。

`ProviderRegistryValidatorTest`：

1. 有效普通 Provider 校验通过。
2. 有效 `mock` Provider 校验通过。
3. 空注册表清晰失败。
4. 普通 Provider 缺 key 或 base URL 时点名失败。
5. 错误消息不包含凭证内容。

### 重启集成测试

新增默认测试门禁会执行的 `ProviderConfigRestartTest`，不标记
`@Tag("integration")`，使用 mock/本地假配置、两个 Spring Context 和同一个
临时 SQLite 文件，不依赖真实网络或外部 API key：

1. 第一次启动用有效 YAML seed 创建 Provider。
2. 通过 `ProviderRegistry.save()` 模拟管理台轮换 key 和 base URL。
3. 关闭第一个 Context。
4. 第二次以相同数据库、空 YAML key 启动 Servlet Context。
5. 断言第二次启动成功，且数据库仍保留管理台写入的值。

另加首次启动失败场景：数据库为空且 YAML key 为空时，不产生 Provider
记录，LLM 入口返回清晰的启动错误。该场景通过 Bootstrap/Validator 单元测试
覆盖，避免为预期启动失败引入脆弱的整机异常断言。

### 回归门禁

先运行受影响模块：

```bash
mvn test -pl oryxos-provider,oryxos-cli,oryxos-web,oryxos-boot -am
```

再运行完整门禁：

```bash
mvn clean verify
```

## 预计修改范围

- `oryxos-provider`：新增 Bootstrap、Validator 及单元测试。
- `oryxos-cli`：调整 `OryxOsRuntime` 播种接线和 `ChatCommand` 校验。
- `oryxos-web`：调整 `ProviderStartupCheck`。
- `oryxos-boot`：新增跨重启集成测试。
- `oryxos-storage/src/main/resources/schema.sql`：只同步注释，不改表结构。

## 验收标准

1. 数据库已有 Provider 时，启动过程不再对其执行 YAML upsert。
2. 管理台更新的 Provider key/base URL 在重启后保持不变。
3. 数据库已有有效 Provider、YAML key 为空时，`serve` 和 `chat` 可正常使用
   数据库配置。
4. 数据库为空、YAML key 为空时，不写入空记录，LLM 入口清晰失败。
5. 历史无效数据库记录不被自动覆盖，并在 LLM 入口启动时清晰失败。
6. `mock` Provider 与不使用 LLM 的轻命令保持现有行为。
7. 相关测试和 `mvn clean verify` 全部通过。

## PR 交付边界

该 PR 只修复 Provider 启动播种和运行前校验，正文关联 `Closes #42`。不夹带
API 校验重构、密钥存储改造、SQLite 并发配置或其他开放 Issue。
