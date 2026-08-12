# Contract: 管理台 Agent 会话输入区（键盘与偏好）

范围：Web 管理台 `/admin`，Agent 详情 Tab「会话」底部 `.chat-input` 区域。不涉及 REST API 变更。

## 现有发送路径（不变）

- 点击「发送」→ `sendChat()` → `POST /api/v1/agents/{name}/session/messages`，body `{ content: string }`
- 禁用条件：`chat.sending === true` 或 `!chat.input.trim()`

## 键盘契约

监听目标：会话 Tab 内消息 `textarea.gen-draft` 的 `keydown`（冒泡阶段即可）。

| 模式 | 按键 | 行为 |
|------|------|------|
| `enter` | Enter，且非 Shift | 若可发送：`preventDefault`，调用 `sendChat()` |
| `enter` | Shift+Enter | 浏览器默认（换行），不发送 |
| `modifier` | Enter，且无 Ctrl/Cmd | 浏览器默认（换行），不发送 |
| `modifier` | Enter，且 Ctrl 或 Meta | 若可发送：`preventDefault`，调用 `sendChat()` |

「若可发送」= 与发送按钮相同的禁用逻辑；不可发送时不 `preventDefault`（Enter 仍可按需换行，除非已在 enter 模式且为空——空内容时 enter 模式 Enter 不应发送，也不应阻止换行：空内容不 preventDefault）。

## 模式切换 UI

- 位置：`.chat-input` 内，与「发送」按钮同一 `.ops` 行或紧邻上一行
- 控件：二选一（Enter 发送 / Ctrl+Enter 发送），切换后立即生效，无需刷新
- 提示：切换控件旁或下方一行中文，内容与当前模式一致（见 research Decision 5）

## localStorage 契约

| 键 | 值 | 写入时机 |
|----|-----|----------|
| `oryxos.admin.chatSendMode` | `enter` \| `modifier` | 用户切换模式时 |

读取时机：管理台 JS 初始化（或首次进入会话 Tab 前）读取一次；缺省/非法 → `modifier`。

## 非目标（契约外）

- CLI `oryxos chat` 键盘行为
- 其它页面 textarea
- 服务端用户偏好 API
