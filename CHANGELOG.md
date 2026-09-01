# Changelog

本文件记录 OryxOS 的版本变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- 飞书渠道流式回复功能（019 集成到 017）：
  - 立即反馈（< 500ms）：⌨️表情 + 初始卡片（蓝色"🤔 正在思考..."）
  - 过程可见：工具调用实时展示（🔧 正在执行 / ✅ 完成）
  - 打字机效果：累积式回复（每 200 字符或 1 秒更新一次）
  - 优雅降级：API 不可用时自动回退到纯文本
  - 错误友好：红色卡片显示可读错误信息（不含堆栈）

### Changed
- `InboundMessageService` 支持流式监听器，调用 `AgentService.process()` 时传入 `StreamListener`
- `FeishuMessageSender` 新增 `sendCard()` / `updateCard()` 方法支持交互式卡片操作
- `FeishuChannelAdapter` 实现 `createStreamListener()` 创建飞书专用流式监听器

### Fixed
- 修复 3 个 SpotBugs CRLF 日志注入风险（`FeishuStreamListener` / `FeishuChannelAdapter`）

## [0.1.4-RELEASE] - 2026-08-31

### Added
- 本地知识库：解析/切分/向量化索引与双路召回检索（`feat(knowledge)`，#202/#205）。
- 长期记忆语义召回：配置 `embedding.*` 后三路加权融合（015，#207）。
- 飞书 IM 入站渠道（017，#235）。
- 企业微信 AI 机器人长连接入站（#289/#290）；群回复引用原消息（#323）；断线自动重连（#319）。
- 钉钉 Stream 入站渠道 Runtime 装配与部署文档（#328）。
- REST API Key 认证：`/api/v1` 机器调用门禁（018，#249）。
- SSE 流式响应：三端点打字机 + CLI/管理台消费面（019，#263）。
- Tool Policy 工具策略：全局/Agent 级 allow/deny 治理层（020，#315）。
- SMTP 邮件通知渠道与 SMTP 端点白名单（#246）。
- 钉钉/飞书富文本与卡片通知格式（#248）；企业微信 Markdown 通知（#234）。

### Fixed
- API Key 过滤器覆盖 `/api/v2` 端点（#311）。
- 知识库索引跳过软链并强制 realpath 边界（#313）。
- 拦截对管理配置与 SQLite 库文件的读取（#312）。
- Profile 加载时 realpath 复检与 fail-loud（#309）。
- `make_dir` 绑定槽位防软链别名绕过（#64da84d）。
- IM 厂商 notify/inbound 默认 `http.allowed_domains` 对齐（#321）。
- 厂商 webhook 2xx 业务错误 fail-loud（#316/#317）。
- 文件工具写前 mutation guard 复检、Skill/Knowledge/AGENT.md 写保护强化（#273/#297–#308 等）。
- Profile YAML 严格校验：cron/ZoneId、列表字段类型、notify_channels/schedules 对象形态（#265–#267/#281–#296）。
- MCP `mcp_servers.yaml` 畸形配置 fail-loud（#271）。
- `WorkspaceWatcher` 对 `AGENT.md` 大小写不敏感（#243）。
- 无触发足迹时归档记忆修复（#208）。
- Windows 测试：路径分隔符与软链/POSIX 假设守卫（#262/#310）。
- 管理台登录页静态资源 401 白屏（018 配套）。
- 依赖升级：Tomcat 10.1.59、pdfbox、commons-lang3、springdoc/swagger-ui（#277/#287）。

### Security
- HTTP DNS rebinding：绑定已校验 DNS 解析结果（#278）。
- SSRF：IPv6 Teredo/6to4/ISATAP/IPv4-compatible 嵌入地址展开后私网拦截（#253/#269/#279/#280）。
- HTTP 重定向跨域剥离 `Authorization`/`Cookie`/`Api-Key`/`Private-Token`/`X-Access-Token`/`Deploy-Token` 等凭证（#241/#259/#291/#294）。
- HTTP 302 重定向剥离 `Content-Type` 等 body 头（#275）。
- 拒绝向 `channels.yaml`/`mcp_servers.yaml`/共享 Skill 实体直接写入（#237/#238/#239）。
- `MEMORY.md` 软链别名变异拦截（#251）。
- `http_request` 拒绝不支持的方法（#257）。

### Docs
- 路线图 v0.2/v0.3 交付状态与 HITL/Tool Policy 调整。
- SMTP 与通知白名单文档同步。
- README 版本徽章升级至 0.1.4。

## [0.1.3-RELEASE] - 2026-08-18

### Added
- Windows PowerShell 安装脚本（`feat(scripts)`，#176）。
- Provider 连通性测试（#97）。
- LLM Provider HTTP 调用 connect/read 超时（#88）。
- 管理台重复登录失败按用户名与来源 IP 锁定（`feat(web)`，#92）。

### Fixed
- Provider `/models` RestClient 设置 connect/read 超时，避免挂起阻塞管理台（#178）。
- 控制台日志显式 UTF-8 charset，修复中文 Windows 终端乱码（#176）。
- `download_file` 在 `createDirectories` 之后、`Files.write` 之前复检 `FILE_WRITE`（#180）。
- `edit_file` 在 `readString` 前复检 `FILE_WRITE`（#184）。
- `list_dir` 列目录前复检 `FILE_READ`（#175）。
- `write_file`/`append_file`/`edit_file` 写前复检 `FILE_WRITE`（#166）。
- `download_file` 写前复检 `FILE_WRITE`（#144）。
- `delete_file` 目录检测不跟随软链（#146）。
- `copy_file`/`move_file` 写前复检沙箱；拒绝目录目标（#148）。
- `grep`/`glob` 遍历前解析目录软链；`**/` 匹配 walk 根目录文件（#150）。
- `json_extract` 区分 JSON `null` 与路径缺失（#152）。
- Tool RestClient connect/read 超时（#138）。
- 本地 vLLM/Ollama 端点强制 HTTP/1.1（#133）。
- Provider ChatModel 缓存按 provider name 有界（#84）。
- shell stdout/stderr 与 CLI stdin 按默认 charset 解码（#129/#121）。
- `serve`/`gateway` 无交互环境抛出 `UnsupportedUserInteraction`（#119）。
- `notify` 默认 channel 从全局 registry 读取（#123）。
- HTTP write 与 `notify` 重定向每跳复检白名单（#112/#117）。
- 审计写入失败不再掩盖 LLM/Tool 主流程结果（#95）。
- `MEMORY.md` 追加串行化与原子写入（#86）。
- Markdown 记忆条目 section header 消毒（#164）。
- 管理台 `BasicAuthFilter` 应用登录锁定（#161）。
- 会话聊天新回复自动滚底；重载后保留滚动位置（#100/#101）。
- Skill 导入 GitHub 路径空格编码为 `%20`（#142）。
- HTTPS 反代下 `Secure` cookie 尊重 `X-Forwarded-Proto`；登录使旧 session 失效（#90）。
- 恢复 `main` CI 绿：spotless + SpotBugs（#115）。
- CI spotless 快速失败与 deploy-pages 超时（#162）。
- 默认模型示例改为 `deepseek-v4-flash`（#80）。

### Security
- 修复 shell 白名单 bypass（#98）。
- HTTP 跨域重定向时剥离 `Authorization`/`Cookie` 等敏感凭证头（#182）。
- `web_search` 重定向 SSRF：`Redirect.NEVER` + endpoint `HTTP_READ` 校验（#156）。
- NAT64/IPv4-mapped 嵌入 SSRF 拦截（#127）。
- HTTP 请求必须 http(s) scheme；HTTP_READ 必须 http(s) host（#136/#131）。
- Skill 导入 SSRF 拦截 IPv6 ULA（#110）。

### Docs
- shell/HTTP 沙箱文档与实现对齐（#125）。
- HTTP 白名单文档区分 HTTP_READ vs HTTP_REQUEST（#104）。
- 英文文档 allowlist → whitelist（#105）。
- 修复过时 jar 路径与文档链接（#106）。
- 中文快速开始补充 Windows 说明（#102）。
- 网站侧边栏增加 Auth 页（#99）。
- README 版本徽章升级至 0.1.3。

## [0.1.2-RELEASE] - 2026-08-10

### Added
- 管理台概览页接入实时数据源，新增会话统计端点（`feat(web/api)`）。
- Agent 会话聊天支持可配置的键盘发送方式（`feat(web)`）。
- Web 端支持选择模型、编辑 Agent 详情（`feat(web)`）。
- 渐进式 Agent Skill 加载（`feat`）。

### Fixed
- `/api/v1/agents/{name}/invoke` 改为无状态调用：每次用独立内存会话，不再泄漏隐藏会话（#68，Closes #49）。
- 会话并发写入丢消息修复：进锁重读 + CAS 条件更新（冲突返回 409）、SQLite WAL + `busy_timeout`、审计 fail-closed、持久历史按整轮截断（#69，Closes #43）。
- 更新安全基线与依赖扫描，处理传递依赖 CVE 门禁（#71）。
- `WorkspaceWatcher` 递归监听 Agent 子目录，修复 `AGENT.md` 变更漏检（#63，Closes #61）。
- 恢复 `main` 分支 CI 绿：spotless 格式、P3C 误报/魔法值、掩码测试对齐（#64）。
- 修复七项高风险安全与并发问题（#58）。
- Provider 数据库配置跨重启保留（#56）。
- 支持 `baseUrl` 自带版本段的 OpenAI 兼容端点（如智谱 GLM `/api/paas/v4`）。
- 统一 `baseUrl` 约定（不含 `/v1`），修复 OpenAI 兼容 Provider 404。
- Provider 校验前移到端口绑定之前，避免误报启动失败。
- 恢复管理台认证；用户账户管理命令不再强制要求 LLM api-key。
- `bin/start.sh` 在 jar 未构建时正常报错，不再静默吞掉。
- 更新默认模型示例为 `deepseek-v4-flash`。

### Changed
- 复用 `requireAgent` 收敛重复的存在性检查（#65）。
- Skill 页面简化为纯 CRUD（`refactor(web)`）。

### Security
- 明确 shell 沙箱边界：默认命令白名单移除 `python3`，锁定为 `ls`/`cat`/`echo`/`grep` 最小权限，并补文档警示解释器会放大 Agent 权限（#70，Closes #41）。

### Docs
- 新增根 `CONTRIBUTING.md` 并链接贡献指南（#60）；澄清 Agent 目录相关措辞（#59）。
- 对齐 notify channel 文档与当前实现；修正 `serve` 命令过时的帮助文本（#62）。
- 修复 OryxOS 概览 logo 链接失效（#55）。
- README 版本徽章升级至 0.1.2（#72）。

## [0.1.1-RELEASE] - 2026-07-23

### Added
- 管理台认证（012-web-auth）。
- 管理台概览统计从 API 加载。

### Fixed
- HTTPS 请求下的认证 Cookie 安全属性。

### Docs
- 网站 canonical/sitemap 指向 github.io 域名。

## [0.1.0-RELEASE] - 2026-07-22

首个发行版：面向企业场景的 Distributed AI Agent OS 运行时内核（9 个 Maven 模块）。

### Added
- 自实现 ReAct Loop、`PromptBuilder`、`ToolExecutor`。
- Provider 显式映射（`ProviderService`），基于 Spring AI Alibaba 做协议转换。
- 长期记忆体系（`MemoryService`、`MEMORY.md`、`save_memory`/`recall_memory`）。
- 内置 Tool（文件/Shell/HTTP/notify）+ MCP Client + `SandboxChecker` 白名单沙箱。
- 「一个目录 = 一个 Agent」的 `AGENT.md` 加载（frontmatter → Profile + 正文注入 system prompt）。
- Web REST API（`/api/v1`）与 CLI 子命令（`init`/`chat`/`serve`/`gateway` 等）。
- SQLite 持久化与 `tool_invocations` / `llm_calls` 审计表 Day-One 写入。

[0.1.4-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.3-RELEASE...v0.1.4-RELEASE
[0.1.3-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.2-RELEASE...v0.1.3-RELEASE
[0.1.2-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.1-RELEASE...v0.1.2-RELEASE
[0.1.1-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.0-RELEASE...v0.1.1-RELEASE
[0.1.0-RELEASE]: https://github.com/oryx-labs/oryxos/releases/tag/v0.1.0-RELEASE
