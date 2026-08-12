# Data Model: Agent 会话发送快捷键可配置

本特性无服务端实体与 SQLite 变更。仅客户端偏好。

## 发送模式偏好（ChatSendModePreference）

| 字段 | 类型 | 说明 |
|------|------|------|
| `mode` | enum string | `enter` \| `modifier` |

### 枚举语义

| 值 | 用户可见标签 | 发送 | 换行 |
|----|--------------|------|------|
| `enter` | Enter 发送 | Enter（无 Shift） | Shift+Enter |
| `modifier` | Ctrl+Enter 发送（Mac 文案用 ⌘+Enter） | Ctrl+Enter 或 Cmd+Enter | Enter |

### 默认值

- 未写入或非法值 → `modifier`（与 spec Assumptions 一致）

### 持久化

| 属性 | 值 |
|------|-----|
| 介质 | `window.localStorage` |
| 键 | `oryxos.admin.chatSendMode` |
| 值 | `enter` 或 `modifier` |
| 作用域 | 管理台全局（非 per-agent、非 per-session） |

### 校验规则

- 读取时：非 `enter`/`modifier` 之一则丢弃并写回默认 `modifier`（可选，implement 时至少内存回退即可）
- 写入时：仅在用户切换模式时 `setItem`；不在每次按键时写

### 状态转换

```text
（无存储） ──首次访问──► modifier（默认）
     │                        │
     │ 用户选 Enter 发送       │ 用户选 Ctrl+Enter 发送
     ▼                        ▼
   enter ◄────── 切换 ──────► modifier
```

### 与运行时 UI 的绑定

- Vue `ref`/`reactive` 字段（建议名 `chatSendMode`）在 `onMounted` 或 setup 初始化时从 localStorage  hydrate
- 切换控件变更时：更新 ref + `localStorage.setItem`
- 不进入 Session / Agent 后端模型
