# Feature Specification: Agent 会话发送快捷键可配置

**Feature Branch**: `012-agent-send-shortcut`

**Created**: 2026-07-28

**Status**: Draft

**Input**: User description: "计划优化下 agent 的发送消息交互，可以选择 ctrl+enter 或 enter 发送消息"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 用快捷键发送消息 (Priority: P1)

运维或业务用户在 Web 管理台打开某个 Agent 的「会话」页，在底部多行输入框里写好消息后，希望不用点「发送」按钮，而是用键盘习惯的方式一键发出，减少鼠标操作、加快连续对话。

**Why this priority**: 这是本次优化的核心价值；没有快捷键时，长对话里反复点按钮成本高，且与常见 IM / 聊天产品体验差距明显。

**Independent Test**: 在任意 Agent 会话页输入非空消息，分别按所选发送快捷键，确认消息发出、输入框清空，且与点击「发送」行为一致。

**Acceptance Scenarios**:

1. **Given** 用户已打开 Agent「会话」且输入框有非空内容、当前未在发送中，**When** 用户按下当前配置为「发送」的快捷键，**Then** 系统发送该消息并清空输入框（与点击「发送」相同）。
2. **Given** 用户已打开 Agent「会话」且输入框为空或仅空白，**When** 用户按下发送快捷键，**Then** 系统不发送消息（与「发送」按钮禁用逻辑一致）。
3. **Given** Agent 正在处理上一条消息（发送中），**When** 用户按下发送快捷键，**Then** 系统不重复发送。

---

### User Story 2 - 在两种发送模式间切换 (Priority: P1)

用户习惯不同：有人希望 **Enter 直接发送**（换行用 Shift+Enter），有人希望 **Enter 换行、组合键发送**（如 Ctrl+Enter，在 macOS 上为 Cmd+Enter）。用户需要在两种模式间显式选择，并立即生效。

**Why this priority**: 需求明确为「可以选择」两种方案；仅固定一种快捷键无法满足两类用户。

**Independent Test**: 切换发送模式后，无需刷新页面即可验证 Enter 与组合键行为对调，且界面提示与所选模式一致。

**Acceptance Scenarios**:

1. **Given** 用户处于「Enter 发送」模式，**When** 在输入框按 Enter（未按 Shift），**Then** 发送消息；**When** 按 Shift+Enter，**Then** 在输入框插入换行、不发送。
2. **Given** 用户处于「组合键发送」模式，**When** 按 Enter（无修饰键），**Then** 插入换行、不发送；**When** 按 Ctrl+Enter（macOS 上 Cmd+Enter），**Then** 发送消息。
3. **Given** 用户在会话输入区附近切换发送模式，**When** 选择另一种模式，**Then** 后续按键行为按新模式执行，且输入区旁展示当前模式的简短说明（例如「Enter 发送，Shift+Enter 换行」或「Ctrl+Enter 发送，Enter 换行」）。

---

### User Story 3 - 记住我的选择 (Priority: P2)

用户在同一浏览器里多次使用管理台时，希望上次选的发送模式下次打开仍然有效，不必每次重新设置。

**Why this priority**: 可配置若无持久化，每次登录或换设备体验差；持久化成本低、收益高。

**Independent Test**: 切换模式后关闭并重新打开管理台（或刷新页面），进入任意 Agent 会话，确认仍为上次所选模式。

**Acceptance Scenarios**:

1. **Given** 用户曾将会话发送模式设为「Enter 发送」，**When** 在同一浏览器再次打开管理台并进入 Agent 会话，**Then** 默认仍为「Enter 发送」且快捷键行为一致。
2. **Given** 用户从未改过设置，**When** 首次使用会话输入，**Then** 使用系统默认模式（见 Assumptions）且界面提示该默认行为。

---

### Edge Cases

- 输入法组合键（如中文输入法选词）与 Enter 冲突时：在「Enter 发送」模式下，若浏览器/输入法尚未确认上屏，行为应遵循浏览器默认（不强制抢 Enter）；验收以英文/已确认输入为主场景。
- 粘贴多行文本后按发送快捷键：整段内容作为一条消息发送，与点击「发送」一致。
- 会话页切换至其他 Agent 或 Tab 再返回：发送模式为全局偏好，不因切换 Agent 而重置。
- 仅空白或换行的输入：发送快捷键与「发送」按钮一样不触发发送。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在 Web 管理台 Agent「会话」消息输入区支持键盘发送，行为与「发送」按钮等价（含禁用条件：空内容、发送中）。
- **FR-002**: 系统 MUST 提供两种互斥的发送模式供用户选择：
  - **模式 A（Enter 发送）**：Enter 发送；Shift+Enter 换行。
  - **模式 B（组合键发送）**：Enter 换行；Ctrl+Enter 发送；在 macOS 上 Cmd+Enter 与 Ctrl+Enter 等效于「组合键发送」。
- **FR-003**: 用户 MUST 能在会话输入区附近（同一屏内、无需进入系统级设置页）切换发送模式，切换后立即生效。
- **FR-004**: 系统 MUST 在输入区旁展示与当前模式一致的快捷键说明（中文，简短一行）。
- **FR-005**: 系统 MUST 在同一浏览器内持久化用户所选发送模式，并在后续访问管理台时恢复；未设置过时使用默认模式（见 Assumptions）。
- **FR-006**: 系统 MUST NOT 因本特性改变后端会话 API 契约或消息格式；仅为前端交互与本地偏好。

### Key Entities

- **发送模式偏好**：用户选择的两种模式之一（Enter 发送 / 组合键发送）；作用范围为管理台全局（所有 Agent 会话共用）；持久于用户浏览器本地。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 在可用性走查中，测试者能在 30 秒内找到发送模式切换并完成一次模式切换，且能正确说出当前模式下的发送键（通过率 100%，样本 ≥ 3 人）。
- **SC-002**: 在两种模式下各完成 5 次连续发送（含至少 1 次多行输入），成功率 100%，且与点击「发送」结果一致。
- **SC-003**: 刷新页面或重新打开管理台后，100% 恢复上次所选发送模式（同一浏览器，连续 3 次验证）。
- **SC-004**: 支持工单或内部反馈中，与会话「只能点按钮发送」相关的体验抱怨在发布后一个迭代内可观测为下降（定性：产品负责人确认无新增同类 P2 反馈）。

## Assumptions

- 范围限定为 **Web 管理台** Agent 详情「会话」Tab 的消息输入（与当前「给这个 Agent 发条消息」输入区一致）；CLI `oryxos chat` 不在本特性范围。
- **默认发送模式**为「组合键发送」（Enter 换行、Ctrl/Cmd+Enter 发送），以降低多行草稿误触 Enter 发送的概率；用户可随时改为「Enter 发送」。
- 偏好存储在浏览器本地即可，无需账号级服务端同步（管理台当前无用户账号体系）。
- 「发送」仍仅通过现有会话消息接口提交；不新增草稿自动保存、不改动 ReAct 执行逻辑。

## Out of Scope

- 自定义任意快捷键组合（仅支持上述两种预设模式）。
- 移动端软键盘专用布局或手势。
- 其他 Channel（CLI、未来第三方 IM）的发送键配置。
