# Quickstart: 新建 Agent 时的已安装 Skill 查询筛选验收

**Feature**: 028-agent-skill-filter | 契约详见 [contracts/skill-picker.md](contracts/skill-picker.md) |
数据模型 [data-model.md](data-model.md)

## 前置

```bash
mvn -q -DskipTests package          # 编胖 jar（含管理台前端，frontend-maven-plugin 跑 npm run build）
bin/start.sh 8080                    # 同进程提供 /admin/ + REST；零 key 可 boot（mock provider）
# 浏览器打开 http://localhost:8080/admin/
```

> 筛选不需要任何真实 LLM key——只验管理台 Skill 选择器行为。为制造「Skill 量大」场景，
> 提前在管理台「Skill 列表」页多新建/导入几个 Skill（名称与描述里混入 pr / git / weather 等可区分词），
> 或直接往 `.oryxos/skills/` 丢若干 `SKILL.md` 目录后重启。

## V1 — 筛选收窄 + 空结果（US1 / FR-001~003 / SC-001）

1. 管理台 → Agents → 「+ 新建 Agent」。
2. 「Skill 绑定」区上方出现搜索框；空时下方列出全部已安装 Skill。
3. 在搜索框输入 `pr`：
   - 期望：列表实时收窄为名称或描述含 `pr`（不区分大小写）的项；其余隐藏。
   - 期望：输入 `  PR `（首尾空格、大写）命中结果与上一步一致（去空格、不区分大小写）。
4. 输入一个不存在的关键词如 `zzz-nope`：
   - 期望：显示「无匹配 Skill」提示，非空白。
5. 清空搜索框：
   - 期望：恢复显示全部已安装 Skill。

## V2 — 筛选不丢已选 + 隐藏提示（US2 / FR-004、FR-004a / SC-002）

1. 新建 Agent，搜索框空：勾选 Skill A、B（记下名字）。
2. 输入只命中 Skill C 的关键词（A、B 不命中）：
   - 期望：列表只剩 C；A、B 不可见但其勾选态被保留（提交时仍生效）。
   - 期望：出现「当前筛选隐藏了 2 项已选」提示。
3. 点击该提示：
   - 期望：A、B 纳入视野（选中态仍是勾选）；`query` 未被清空。
4. 改关键词为命中 A、B 之一：期望提示计数实时刷新为新的隐藏数。
5. 清空搜索框：期望 A、B 仍为勾选态。不点「创建」即可核验「已选不丢」不变量。

## V3 — 批量全选/清空当前（US3 / FR-006，P2）

1. 搜索 `git`，结果 4 项 → 点「全选当前」：期望 4 项全勾选；视野外已选项（先勾好的某个非 git Skill）保持不变。
2. 点「清空当前」：期望仅这 4 项取消；视野外已选项仍选中。
3. 搜索 `zzz-nope`（空结果）：期望「全选当前 / 清空当前」禁用或无效果。

## V4 — 编辑绑定同样可筛选（US4 / FR-007，P2）

1. 选一个已存在 Agent 进「基本信息」编辑 → skills 区出现与新建页一致的搜索框。
2. 该 Agent 已绑定 Skill X：输入不命中 X 的关键词 → X 隐藏但保留勾选；清空 → X 仍勾选。
3. 改一项后点「保存绑定」→ 期望请求 `PUT /api/v1/agents/{name}/skills`，body 含全部 `selected`（含曾被筛选隐藏的 X）；
   下一轮对话生效（012 既有行为）。

## V5 — 纯函数单测（R4 / SC-003 逻辑层）

```bash
cd oryxos-web/src/main/frontend
npm run test      # 既有 node --test，含新增 src/skill-filter.test.js
# 期望：filterSkills（空串全返 / 去空格 / 大小写 / name+description 命中 / 空描述仅按 name）
#      hiddenSelectedCount、selectAllVisible、clearVisible 全绿
```

> `mvn verify` 全量门禁会跑前端构建（含 `npm run build`）；前端测试由 `npm run test` 在构建期执行（与现状一致）。

## V6 — 量级不卡顿（SC-003）

1. 准备 ≥200 个已安装 Skill（往 `.oryxos/skills/` 批量建目录或脚本生成）。
2. 新建 Agent，在搜索框连打短词如 `a` → `ab` → `abc`：
   - 期望：每次键入列表即时收窄，键入不被卡顿、不丢字符（无具体毫秒承诺，以「不阻塞打字」为准）。
