---
name: oryxos-admin-ui
description: >-
  生成或扩展 OryxOS 的 Web 管理台（web manager）前端——Vue 3 + Vite 单页应用，视觉与官网首页
  （website/，VitePress）完全同源：深色底 + 橙色主色、Inter/JetBrains Mono。内置这套设计 token、
  工程约定（base '/admin/'、产物落 oryxos-web static/admin、SPA 路径回落）、组件与三态规范、验收清单。
  当用户要「做/生成管理台前端、加一页 admin 页面（如 Agent 管理页）、让管理台跟首页风格一致、
  扩展 /admin 界面」时使用。仅生成只读或调既有 REST 的前端，不写后端、不自创配色。
argument-hint: "要生成/扩展的页面（如：会话列表页 / Agent 管理页），留空则生成整套只读管理台"
---

# OryxOS 管理台前端生成规范

本 skill 是 OryxOS Web 管理台（`/admin`）前端的**唯一风格与工程口径来源**。生成或扩展管理台页面时读它、照它做，保证每次产出跟官网首页同一个气质、可复现、能被 Spring 直接托管。

## 何时用 / 不用

- **用**：生成整套只读管理台（26 节）、给管理台加页（30 节的"Agent 管理"页：列表 + 新建表单 + 编辑/删除）、调整管理台样式使其与首页一致。
- **不用**：后端 REST（那是 spec-kit 的 Java 管道）、认证鉴权（核心阶段内网假设）、脱离下方 token 的自创配色。

## 一、设计 token（唯一风格来源，值取自 `website/.vitepress/theme/custom.css`，一个字别自创）

深色主题 + 橙色强调，跟首页完全一致。生成的 Vue 工程里放一份 `src/styles/tokens.css` 并全局引入：

```css
:root {
  /* 背景层次 */
  --bg:        #000000;   /* 页面底 */
  --bg-soft:   #111111;   /* 卡片 / 面板 */
  --bg-mute:   #1a1a1a;   /* 悬浮 / 选中块 */
  --border:    #222222;   /* 分隔线 / 边框 */
  /* 主色（橙）——仅用于强调：激活项、链接、数值高亮、主按钮。不铺大面积 */
  --brand-1:   #c2550a;   /* 深 */
  --brand-2:   #ea6a00;   /* hover / 强调 */
  --brand:     #f97316;   /* 主 */
  --brand-soft: rgba(249, 115, 22, 0.12);
  /* 文字 */
  --text-1:    #f5f5f5;   /* 主 */
  --text-2:    #a3a3a3;   /* 次 */
  --text-3:    #666666;   /* 弱 / 占位 */
  /* 状态 */
  --ok:        #22c55e;   /* 成功 */
  --err:       #ef4444;   /* 失败 */
  --warn:      #f97316;   /* 警告（复用橙） */
  /* 字体 */
  --font-base: 'Inter', 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', ui-monospace, monospace;
  /* 形状 */
  --radius:    5px;       /* 小圆角，克制 */
}
```

- 正文用 `--font-base`；**ID / session_id / JSON / cron / token 数这类机读值一律 `--font-mono`**。
- 主色只点睛，不做大色块背景；整体克制、留白足。

## 二、工程约定（决定它能不能被 Spring 托管）

- **技术栈**：Vue 3 + Vite（跟 `website/` 同生态；管理台是 app，用普通 Vue SPA，不用 VitePress）。源码在 `oryxos-web/src/main/frontend/`。
- **base 与产物**：`vite.config` 设 `base: '/admin/'`、`build.outDir` 指到 `../resources/static/admin`（即 `oryxos-web/src/main/resources/static/admin/`），资源用相对路径。
- **SPA 路径回落**：Spring 对 `/admin/**` 未命中路径回落 `admin/index.html`；`GET /api/v1/**` 不受影响（后端接线，提醒 plan 处理）。
- **数据来源**：只调 `/api/v1/**`。只读页调 `GET`（`/sessions`、`/profiles`、`/tools`、`/memory`、`/info`）；管理页（30 节）调 `POST/PUT/DELETE /agents`。开发期 `npm run dev` 起热更、代理 `/api` 到 `:8080`；发布形态是"打进 fat JAR、一个进程托管一切"。
- **错误展示**：后端统一 `ErrorBody`（`errorCode`/`message`/`timestamp`）；页面出错就把 `message` 显示出来，不吞、不弹裸堆栈。

## 三、布局与组件规范

- **骨架**：左侧竖直深色导航（顶部 `/logo.svg` + "OryxOS 管理台"）+ 右侧内容区。
- **导航项（只读版五项）**：会话列表、Profile 列表、Tool 列表、长期记忆、运行状态。
- **表格**：深色底、行分隔 `--border`；机读列等宽字体；长文本可折叠。
- **状态**：小圆点 / 标签——成功 `--ok`、失败 `--err`、警告 `--warn`；激活导航项用 `--brand` 左边条或文字色。
- **三态必备**：加载中（骨架/spinner）、空数据（`--text-3` 占位文案）、错误（红条 + message）——**任何一页都不许白屏**。
- **响应式**：窄屏导航收起为顶部抽屉；表格横向可滚动，不撑破布局。

## 四、生成 / 扩展流程

1. 读本 skill 的 token 与约定；若 `oryxos-web/src/main/frontend/` 已存在，先看它现有结构、复用 `tokens.css` 与组件，不另起一套。
2. 按需求生成/新增页面（组件 + 路由 + 调用的 REST 端点）。加页时（如 30 节 Agent 管理）：列表用 `GET`，新建/编辑用表单调 `POST/PUT`，删除二次确认调 `DELETE`，全部复用同一套 token 与表格/表单组件。
3. 确保 `vite.config` 的 `base`/`outDir` 正确、`npm run build` 产物落在 `static/admin/`。
4. 过一遍验收清单。

## 五、验收清单（做完自查）

- 配色/字体/圆角全部来自上面的 token，无自创色值；主色只用于强调。
- 每页三态（加载/空/错）都有，无白屏；错误展示后端 `message`。
- 只调 `/api/v1/**`；只读页无写操作入口（26 节）；管理页写操作有二次确认（30 节）。
- `npm run build` 产物在 `oryxos-web/src/main/resources/static/admin/`，`base: '/admin/'`，刷新子路由不 404（依赖 Spring 回落）。
- 窄屏可用；视觉跟 `http://localhost:8080`（首页）并排看是同一套设计语言。

> 不要为了"更漂亮"引入外部 UI skill 或大型组件库来盖过这套 token——一致性 > 花哨。确需组件库时选无主题/可完全被 token 覆盖的（headless）。
