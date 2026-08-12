# Changelog

本文件记录 OryxOS 的版本变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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

[0.1.2-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.1-RELEASE...v0.1.2-RELEASE
[0.1.1-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.0-RELEASE...v0.1.1-RELEASE
[0.1.0-RELEASE]: https://github.com/oryx-labs/oryxos/releases/tag/v0.1.0-RELEASE
