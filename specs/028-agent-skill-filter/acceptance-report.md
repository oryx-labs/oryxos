# Acceptance Report: 新建 Agent 时的已安装 Skill 查询筛选

**Feature**: 028-agent-skill-filter | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

## 自动化验证（已通过）

### A1. 纯函数单测（quickstart V5）✅

`oryxos-web/src/main/frontend/src/skill-filter.test.js`，11/11 pass（3 chat-scroll + 8 skill-filter）：

- `filterSkills`：空 query 全返 / 去首尾空格 / 不区分大小写 / name OR description 命中 / 空描述仅按 name。
- `hiddenSelectedCount`：被筛选隐藏的已选项计数。
- `selectAllVisible` / `clearVisible`：并集去重 / 差集；视野外已选项不变。
- `renderSet`：`showHidden=false` 仅 visible；`showHidden=true` 把隐藏的已选项纳入视野并标 `hidden`。

```bash
cd oryxos-web/src/main/frontend && npm run test   # 11 pass / 0 fail
```

### A2. 全量构建（quickstart V5 / T019）✅

`mvn clean package -DskipTests` → BUILD SUCCESS（23.5s）。frontend-maven-plugin 在
`generate-resources` 阶段跑 `npm install` → `npm test`（含 skill-filter.test.js）→ `npm run build`，
三项全绿，胖 jar 重新生成。

### A3. 前端编译（回归零破坏）✅

`npm run build`（vite 6）独立验证通过：19 模块转换、产物 `index-CHbykoRc.js`（273.86 kB）。
模板语法、computed 引用（`agentCreate`/`agentBinding` 在 computed factory 中惰性求值，无 TDZ）、
新 CSS 类均编译通过。

### A4. 集成冒烟（新 jar 起服务）✅

`bin/start.sh 8080` 起新胖 jar → 健康检查通过：

- `GET /admin/` → HTTP 200，HTML 引用新 bundle `assets/index-CHbykoRc.js`（与本地 build 产物一致）。
- `GET /api/v1/skills` → 返回已安装 Skill 列表，每项含 `name` + `description`（即 `filterSkills` 匹配字段）。
- `GET /api/v1/health` → `{"status":"ok"}`。

## 待人工浏览器验收（T018 / T020）

以下为 UI 交互流，仓库无浏览器自动化基建（无 Playwright/Cypress），需人在浏览器里走一遍
（[quickstart.md](quickstart.md) V1~V4、V6）：

| 项 | 验收 | 覆盖的 FR/SC |
|---|---|---|
| V1 | 输入收窄、空结果「无匹配 Skill」、清空恢复全部 | FR-001~003/005、SC-001 |
| V2 | 隐藏已选项保留选中、「N 项已选被当前筛选隐藏」提示、点击纳入视野 | FR-004/004a、SC-002 |
| V3 | 全选当前 / 清空当前、视野外不变、空结果禁用 | FR-006 |
| V4 | 编辑页同型、保存 body 含全部已选（含隐藏项） | FR-007 |
| V6 | ≥200 项连打不卡顿不丢字符 | SC-003 |

> 逻辑正确性已由 A1 单测覆盖；UI 绑定（`v-model` 不丢已选、computed 响应式刷新）由 A2/A3
> 编译期保证 + A4 集成冒烟。V1~V4/V6 是「人眼确认体验」的最后一道，非逻辑门禁。

## Constitution 复核

| 原则 | 实际实现 | 结论 |
|---|---|---|
| IV 目录=Agent / Skill 软连接 + 渐进披露 | 筛选只改 `App.vue` 选择器显示层；`filterSkills` 只读 `name`/`description`，不读 `body`（正文不预载）；绑定仍走既有 `PUT /agents/{name}/skills` 落软连接 | ✅ |
| V/VI/VII/VIII | 无 tool/LLM 调用、无沙箱/文件动作、无服务端状态、无新表 | ✅ |
| 模块约束 | 全部改动在 `oryxos-web/src/main/frontend`，无新模块 | ✅ |

## T21 决策：CLAUDE.md 不改

本特性为管理台 Skill 选择器的查询筛选 UX，属既有「Skill 绑定」能力的体验增强，不构成新的
核心能力或宪法级行为——CLAUDE.md 记录架构/宪法/核心能力，不为单个 UI 控件补行。按 T021
「无则跳过（避免无谓改动）」条款跳过。

## 交付物

- 新增：`oryxos-web/src/main/frontend/src/skill-filter.js`、`src/skill-filter.test.js`
- 改动：`src/App.vue`（import + `skillFilter` 态 + 6 computed + 2 处 skill-picker 模板 + CSS）、`package.json`（test 脚本追加）
- 零：新 Java 文件 / 新 REST 端点 / 新表列 / 新依赖
