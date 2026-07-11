# 第19节验收报告：Notify——结果主动送出去的统一出口

**日期**: 2026-07-11 | **分支**: `class-19` | **任务**: 12/12 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecBugs 全部门禁。`BUILD SUCCESS`，测试 74 个全过：

```text
oryxos-core     34 | oryxos-provider 14 | oryxos-storage 15
oryxos-tool     11（WebhookNotifyAdapterTest 4 + NotifyToolsTest 7）——本模块首批实码
```

过程中被门禁拦下并修复：Spotless 格式 ×1、SpotBugs EI_EXPOSE_REP2 ×1（RestClient 改 `mutate().build()` 防御性副本）——安全规则未做任何排除。

### 2. harness 测试类逐一对号 ✅（课件分批口径）

**第一批（本节判卷）**：`WebhookNotifyAdapterTest`——POST body 带 content、`@DisplayName("换通知目标只改配置零代码")`（URL 来自 config 非硬编码）、`@DisplayName("webhook返回5xx_异常向上抛不静默吞掉")`、config 缺 url 零请求；`NotifyToolsTest` 可测集 7 个——`@DisplayName("notify_channels未配置_明确报错不静默失败")`、`@DisplayName("channel参数缺省_取第一个渠道")`、"default" 字面量、指定类型命中/未命中不回退、上下文缺失、content 缺失。

**留待后续节（文件头注释已注明）**：InOrder `发送前必须先过白名单校验`（enforce 先于 send）→ 24 节 Sandbox 就位后补入 NotifyToolsTest；notify 经 ToolRegistry 的端到端审计链 → 20 节。

### 3. 交付物存在性核对 ✅

- 代码：`NotifyChannelAdapter`（`send(NotifyTarget, String)` 签名逐字保真）/ `NotifyTarget` / `WebhookNotifyAdapter` / `NotifyTools`（OryxTool 形态，停点确认项）→ oryxos-tool（notify 包 + builtin 包，课件包名字面量）
- 配置：Profile `notify_channels` 字段——16 节已提前交付，本节零配置改动
- 接口中立性 grep：主代码 wecom/feishu/dingtalk 等渠道特有词**零命中**
- 停点确认过的项：OryxTool 形态适配、JDK HttpServer 替代 MockWebServer、resolveNotifyChannel 归 NotifyTools 私有、不改 OryxOsRuntime

### 4. 前序节回归 ✅

16/17/18 节 63 个测试断言零改动全绿（上表即证据）；本节未触碰任何前序模块主代码（仅 oryxos-tool pom + 新文件）。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | 检查位注释在 NotifyTools:74（24 节接线，与 http_post 共享白名单）；notify 未装配进运行时，Sandbox 就位前不放开涉外工具 |
| ② | 审计成败都落库 | notify 走 ToolExecutor 既有 tool_invocations 路径（OryxTool 形态即为此设计），零新增审计逻辑 |
| ③ | 无明文 key | grep 零命中；测试 url 为 example.com 假地址 |
| ④ | session_id 只在 SessionManager 拼 | 本节不触碰 |
| ⑤ | 无异步编程模型 | RestClient 同步调用；grep 零命中 |
| ⑥ | 无 Spring AI 自动执行 | 本节零 Spring AI 依赖（@Tool 归 20 节） |

### 6. 剩余人工项（harness 判不了，等你过）

1. **真实 webhook 验配置**：20 节接线后，给某 Profile 配真实群机器人地址（`url: ${TEAM_WEBHOOK_URL}`），chat 里说"把'测试消息'推送一下"，群里收到（假 webhook 测的是协议，真 webhook 验的是配置）；
2. **接口中立性思维自查**：换企业微信官方 SDK 实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名需要改吗？——应为不需要（grep 已证语汇中立，语义判断留人工）。

## 追加：三家专用渠道 Adapter（用户拍板的扩展，超出课件核心阶段边界）

按课件 6.4 路一新增：`WeComNotifyAdapter`（type: wecom）、`FeishuNotifyAdapter`（type: feishu，同时覆盖 Lark——协议相同仅域名不同）、`DingTalkNotifyAdapter`（type: dingtalk，含可选加签：config 配 secret 即按官方 HmacSHA256 算法拼 timestamp+sign）。配套改造：`NotifyTools` 构造从单 Adapter 改为 `Map<String, NotifyChannelAdapter>` 按 channelType 路由（type 无对应实现 → 报错点名已装配清单）。新增 `VendorNotifyAdapterTest` 6 个（三家 body 格式对约定断言、钉钉加签 URL、5xx 上抛、缺 url 零请求）+ NotifyToolsTest 路由用例。**全仓 81 测试绿**（tool 模块 18）。接口签名零改动——中立性自查就地兑现。

⚠️ 各家 body 格式按官方公开约定实现，测试钉死的是"我们发的形态"；**真实兼容性归人工冒烟**（拿真实群机器人 URL 各发一条）。

## 备注

- 分批边界照课件"实现顺序说明"：本节交付抽象 + webhook 实现 + 工具骨架与第一批 harness；20 节 ToolRegistry 收编、24 节 Sandbox 接线 + InOrder 回归、27/28 节串联全量验证。
- 未 commit/push——同步时机由你决定。
