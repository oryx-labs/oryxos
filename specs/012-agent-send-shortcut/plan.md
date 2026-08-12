# Implementation Plan: Agent 会话发送快捷键可配置

**Branch**: `012-agent-send-shortcut` | **Date**: 2026-07-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-agent-send-shortcut/spec.md`

## Summary

在 Web 管理台 Agent 详情「会话」Tab 的多行输入区增加键盘发送与两种互斥模式（Enter 发送 vs Ctrl/Cmd+Enter 发送），模式可就地切换、localStorage 全局持久化，输入区旁展示中文快捷键提示。实现纯前端：复用现有 `sendChat()` 与 `POST /api/v1/agents/{name}/session/messages`，不改 Java 模块与 REST 契约。

## Technical Context

**Language/Version**: Vue 3（`<script setup>`）+ Vite；宿主构建仍由 oryxos-web frontend-maven-plugin 打包进 `/admin`

**Primary Dependencies**: 无新 npm 包；`keydown` 处理 + `localStorage`

**Storage**: 浏览器 `localStorage` 键 `oryxos.admin.chatSendMode`（见 [data-model.md](./data-model.md)）

**Testing**: 本特性无后端单测；验收以 [quickstart.md](./quickstart.md) 人工走查为主（可选后续 Vitest 组件测，非本 plan 必交付）

**Target Platform**: 桌面浏览器（Chrome / Safari / Firefox）；macOS 上组合键识别 `metaKey`（Cmd）

**Project Type**: 管理台 SPA 单点交互增强（`oryxos-web/src/main/frontend`）

**Performance Goals**: 无；按键处理同步、无额外网络

**Constraints**: 宪法 VII 不适用（无服务端并发变更）；FR-006 禁止改会话 API；与现有 `.chat-input` / `.gen-draft` 样式一致

**Scale/Scope**: 约 1 个 Vue 文件内局部改动（`App.vue` 会话 Tab + script）；可选抽小 composable/constants，避免过度拆分

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 相关性 | 结论 |
|------|--------|------|
| I 自实现 ReAct | 发消息仍走既有 session messages → AgentService | ✅ 不触碰 |
| II Spring AI | 无 | ✅ N/A |
| V 审计 Day One | 发送路径不变 | ✅ 仍经既有 process |
| VII 同步 + 虚拟线程 | 无 Web 层变更 | ✅ N/A |
| IV 模块边界 | 仅前端 | ✅ 不新增 Java 模块 |
| VI 安全 | localStorage 仅存 UI 偏好 | ✅ 无凭证 |

**Gate: PASS**（无违规、无 Complexity Tracking 条目）。

**Phase 1 复验**: 契约仅 UI/localStorage，仍 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/012-agent-send-shortcut/
├── plan.md              # 本文件
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1 验收
├── contracts/
│   └── chat-input-ui.md # Phase 1 UI 行为契约
└── tasks.md             # /speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-web/src/main/frontend/src/
└── App.vue                # 【改】chat 发送模式 state、localStorage、textarea @keydown、模式切换 UI、提示文案

（无 oryxos-core / oryxos-boot 变更）
```

**Structure Decision**: 管理台仍为单文件 App 形态（与 31 节以来一致）；本特性不强制拆组件，除非 implement 阶段 textarea 逻辑超过 ~40 行再抽 `useChatSendMode.js`。

## Complexity Tracking

> 无宪法违规，本节留空。
