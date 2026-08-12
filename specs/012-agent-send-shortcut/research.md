# Research: Agent 会话发送快捷键可配置

Technical Context 无 NEEDS CLARIFICATION。以下为实现决策记录。

## Decision 1：两种模式用枚举字符串持久化

- **Decision**: `enter` | `modifier`（对外文案：「Enter 发送」「Ctrl+Enter 发送」）；非法/缺失值回退默认 `modifier`。
- **Rationale**: 与 spec FR-002 一一对应；字符串便于 localStorage 人工排查。
- **Alternatives**: 布尔 `enterToSend`——可读性略差，等价可行。

## Decision 2：localStorage 键与作用域

- **Decision**: 键名 `oryxos.admin.chatSendMode`；管理台全局（所有 Agent 会话共用），与 spec Key Entities 一致。
- **Rationale**: 项目内尚无 localStorage 先例；加 `oryxos.admin.` 前缀避免与同域其它应用冲突。
- **Alternatives**: sessionStorage——刷新仍保留但关标签丢失，不符合 FR-005「再次打开仍有效」的常规理解。

## Decision 3：keydown 行为实现

- **Decision**:
  - 模式 `enter`：`Enter` 且非 `shiftKey` → `preventDefault` + 调 `sendChat()`；`Shift+Enter` 默认换行。
  - 模式 `modifier`：`(ctrlKey || metaKey) && Enter` → `preventDefault` + `sendChat()`；裸 `Enter` 换行。
  - 发送前校验与按钮一致：`!chat.sending && chat.input.trim()`。
- **Rationale**: 与 Slack/Discord/飞书等常见双模式一致；macOS 必须认 `metaKey`（spec FR-002）。
- **Alternatives**: 全局 document 监听——否，会误触其它输入框。

## Decision 4：模式切换 UI 位置与控件

- **Decision**: 放在 `.chat-input` 内、textarea 下方 `.ops` 区域：小型分段切换（两个 radio 或两个 pill 按钮）+ 一行 `.empty` 风格提示文案（随模式 computed）。
- **Rationale**: 满足 FR-003「输入区附近、同一屏」；不新增设置页。
- **Alternatives**: 仅 hover 提示、无切换——违反 FR-003。

## Decision 5：提示文案（含 macOS）

- **Decision**:
  - `enter`: 「Enter 发送，Shift+Enter 换行」
  - `modifier`: 运行时若 `navigator.platform` 含 `Mac` 则「⌘+Enter 发送，Enter 换行」，否则「Ctrl+Enter 发送，Enter 换行」
- **Rationale**: FR-004 中文一行；Mac 用户认 Cmd 而非 Ctrl。
- **Alternatives**: 固定写 Ctrl——Mac 体验差。

## Decision 6：不引入 E2E 框架

- **Decision**: 验收依赖 quickstart 手工步骤；不为本特性新增 Playwright/Cypress 依赖。
- **Rationale**: 管理台当前无 E2E 基建；改动面小，人工 5 分钟可覆盖 SC-002/SC-003。
- **Alternatives**: Vitest + @vue/test-utils 测 handler——可作为 tasks 可选项，非 gate。
