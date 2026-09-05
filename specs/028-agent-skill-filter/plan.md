# Implementation Plan: 新建 Agent 时的已安装 Skill 查询筛选

**Branch**: `028-agent-skill-filter` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/028-agent-skill-filter/spec.md`

## Summary

管理台「新建 Agent」与「Agent 详情 > 基本信息」两处的 Skill 绑定勾选列表，从平铺全量复选框
升级为可搜索筛选：加一个搜索框，按 name + description（去首尾空格、不区分大小写）实时收窄；
**筛选只影响显示、不影响已勾选状态**（FR-004），被筛选隐藏的已选项保持选中，并以「N 项已选被
当前筛选隐藏」提示 + 一键纳入视野守住安全感（FR-004a，Clarifications 裁决）；P2 加「全选当前 /
清空当前」批量动作。**纯客户端、零新后端接口、零新依赖**——`skills.data` 进入视图时已由既有
`loadSkills()`（`GET /api/v1/skills`）加载完毕，筛选是 Vue computed 的事；绑定存储口径不变
（仍保存为 `agents/<name>/skills/<skill>` 相对软连接，走既有 `PUT /agents/{name}/skills` 与
启动恢复校验），渐进式披露不动（不预载 SKILL.md 正文，与宪法 IV 一致）。

## Technical Context

**Language/Version**: Vue 3.5（`<script setup>` 风格的 Options/Composition 混用，现状为单一 `App.vue`
reactive ref 模式）+ Vite 6；由 `frontend-maven-plugin` 编进 `oryxos-boot` 胖 jar，`bin/start.sh` 同进程
提供 `/admin/`。Java 21 / Spring Boot 3.x 后端在本特性**零改动**。

**Primary Dependencies**: Vue 3（既有 `reactive` / `ref` / `computed`）、Vite（既有 build）；**零新增依赖**。

**Storage**: N/A——纯客户端；Skill 绑定持久化口径不变（相对软连接 + 既有 `PUT /api/v1/agents/{name}/skills`，
落盘与启动恢复校验沿用 012-agent-skill-links 实现）。

**Testing**: 既有 `node --test`（`src/chat-scroll.test.js` 同模式）——为筛选/隐藏计数纯函数补一个单测
`src/skill-filter.test.js`（vanilla JS，不引入 @vue/test-utils）；端到端走管理台手测（quickstart）。
`mvn verify` 全量门禁含 frontend-maven-plugin 的 `npm run build`；前端测试由 `npm run test` 在
构建期跑（与现状一致）。

**Target Platform**: 现代浏览器（管理台前端，打进胖 jar 由 `/admin/` 同进程提供）。

**Project Type**: web-service 内嵌管理台前端（Vue 3 单文件 `App.vue`，无组件抽取现状）。

**Performance Goals**: 200 项已安装 Skill 下连打输入不阻塞键入（客户端 computed substring 过滤，
单次 <16ms，感知无卡顿）——对应 SC-003。

**Constraints**: 纯客户端、零新后端接口、零新依赖；绑定存储与渐进式披露口径双不动；筛选只影响显示
不影响已选（FR-004）；新建页与编辑页两处 skill-picker 共用同一筛选行为（Clarifications Q1）。

**Scale/Scope**: 单文件 `oryxos-web/src/main/frontend/src/App.vue` 局部改动（2 处 skill-picker 模板 +
共享筛选态与纯函数）+ 1 个筛选逻辑单测；约 2 处 skill-picker（新建/编辑），KB 级 diff。无新模块、
无新 Java 文件、无新 REST 端点、无新表/列。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | 不涉及（前端展示层） | ✅ |
| II Spring AI 边界 | 不涉及 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 目录=Agent / Skill 软连接绑定 + 渐进披露 | **关键一致性点**：筛选只改「绑定 UI 的查找体验」，不改绑定真相源——仍存为 `agents/<name>/skills/<skill>` 相对软连接，仍只注入 name+description+读取路径，正文不预载。筛选无权绕过软连接校验或预载 SKILL.md | ✅ |
| V 审计 Day One | 不涉及（无 tool 调用、无 LLM 调用） | ✅ |
| VI 安全是地基 / 沙箱 / 真实路径 | 不涉及——绑定保存仍走既有软连接校验（dangling/escaped/invalid-target/name-mismatch/stale-reference，012 实现）；筛选是进程内显示层，无文件/网络动作 | ✅ |
| VII 同步 + 虚拟线程 | 不涉及（前端） | ✅ |
| VIII 目录配置即 Agent / 状态外置 | 不涉及——无服务端状态新增；筛选查询串是浏览器内瞬态，不落盘 | ✅ |
| 模块约束 | 全部改动在 `oryxos-web/src/main/frontend`（前端资源归 oryxos-web）；不新建模块，无跨模块契约变更，无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/028-agent-skill-filter/
├── plan.md              # 本文件
├── research.md          # Phase 0——关键技术裁决（筛选态归属 / 纯函数 / 无新依赖）
├── data-model.md        # Phase 1——既有实体 + 新增客户端瞬态（无持久化变更）
├── contracts/
│   └── skill-picker.md  # Phase 1——skill-picker 的 UI 行为契约
├── quickstart.md        # Phase 1——端到端验收脚本
└── tasks.md             # Phase 2 输出（/speckit-tasks，本命令不生成）
```

### Source Code (repository root)

```text
oryxos-web/src/main/frontend/
├── package.json                   # 既有；test 脚本追加 skill-filter.test.js
├── src/
│   ├── App.vue                    # 既有；2 处 skill-picker 模板 + 共享筛选态 + 纯函数
│   ├── skill-filter.test.js       # 新增——筛选/隐藏计数/批量勾选纯函数单测（node --test）
│   └── chat-scroll.test.js        # 既有，不动
└── …（vite 配置等不动）
```

**Structure Decision**: 沿用既有「单文件 `App.vue` + 旁挂 vanilla-JS 单测」现状，不抽取 Vue 组件——
本特性改动面小（2 处模板 + 共享态），为它引入组件抽象与 props/emit 边界得不偿失，且与现有风格
一致（chat-scroll 等可复用逻辑也以纯函数旁挂）。两处 skill-picker 共享一个 `skillFilter` reactive
态对象 + 两个纯函数（`filterSkills`、`hiddenSelectedCount`），而非各抄一份。

## Complexity Tracking

> 无 Constitution Check 违背项，本表留空。
