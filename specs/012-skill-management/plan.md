# Implementation Plan: 公共 Skill 渐进式加载、关联与生命周期管理

**Branch**: `012-skill-management`（当前 worktree：`codex/skill-management`） | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/012-skill-management/spec.md`

## Summary

统一仓库中现存的两套 Skill 机制：保留 `.oryxos/skills/<skill>/` 公共包目录，复用并收敛现有安全解析、ZIP 校验、L1 快照、租约和归档能力；Agent 关联唯一由 `.oryxos/agents/<agent>/skills/<skill> -> ../../../skills/<skill>` 标准相对软链接表达。`SKILL.md` 解析器明确处理行尾、BOM、前导空行、frontmatter fence、YAML 1.2 等价语义、name/version grammar、activation/requires 限额与稳定错误。运行时每次请求扫描软链接，只把有效且全局 enabled 的元数据放入 L1，L2/L3 继续经既有 `read_file`/`shell` 渐进读取。

管理面拆为公共包生命周期与 Agent 关联生命周期。Agent 创建时选择的 Skill 必须与 Agent 目录一起生成真实标准链接，任一步失败则新 Agent 整体不可见，且不再生成 `example`。全局禁用保留链接但影响所有 Agent；普通删除扫描全部 Agent，存在关联时返回 409 和完整 Agent 列表；前端二次确认后 force 删除在全局图谱锁与排序 Agent 写锁下重新扫描、解除标准链接并归档公共包。本期不建反向索引，也不建设持久化删除 journal/启动恢复子系统。`AGENT.md skills:` 可兼容解析但不再参与加载、关联或 API 返回。

## Technical Context

**Language/Version**: Java 21；Vue 3 + Vite 6。

**Primary Dependencies**: Spring Boot 3.3.5 / Spring MVC、SnakeYAML、JDK NIO、Apache Commons Compress 1.28.0、既有 `read_file`/`shell` Tool；前端 Vitest + Vue Test Utils。

**Storage**: 文件系统唯一真相源。公共包 `.oryxos/skills/<skill>/`；关联为 Agent 目录内相对软链接；全局禁用 marker 位于公共包；staging 位于 `.oryxos/.staging/skill-import/`；归档位于 `.oryxos/archive/.skills/`。不新增 SQLite 表。

**Testing**: JUnit 5 + Mockito、Spring MockMvc、临时工作区/故障注入/并发测试、Boot E2E、前端 Vitest、Vite production build、quickstart 浏览器验收。

**Target Platform**: Java 21 单 JAR，macOS/Linux/K8s；文件系统必须支持软链接和同 FileStore 原子移动，不支持时明确失败，不退化为复制或 AGENT.md 关联。

**Project Type**: Maven 多模块 Web 应用。领域与文件安全在 `oryxos-core`，装配在 `oryxos-cli`，HTTP/UI 在 `oryxos-web`，E2E 在 `oryxos-boot`；不新建模块，不改 `oryxos-tool`。

**Performance Goals**: 本期删除/force 删除允许 O(Agent 数) 扫描，不设延迟目标、不建缓存或反向索引；请求快照只读有界 frontmatter/文件统计，不预载正文；L1 按名称确定性排序并受 12,000 字符预算约束。

**Constraints**: 同步阻塞 + virtual thread；请求内 snapshot 固定；全局图谱锁 → Agent 名升序写锁为唯一跨 Agent 锁序；标准链接原始 target 必须精确匹配；不得自动扩 Tool 权限；错误/日志不得泄露绝对路径、正文或密钥。

**Scale/Scope**: 单实例、多 Agent；ZIP/解压/文件数限制沿用现有 `SkillLimits`，manifest 的 activation/requires 使用独立有界限制。本期不实现反向索引、分布式锁、跨进程 force-delete 恢复、恢复 API、版本依赖求解、签名或远程 Marketplace。现有 GitHub 导入属于既有能力，不在本 Feature 扩展，最终发布入口以本地 ZIP 契约为准；本地公共 Skill 市场仍是本 Feature 的核心。

## Constitution Check

*GATE: Phase 0 前检查，并在 Phase 1 后复核。*

| 原则 | 设计影响 | Phase 0 前 | Phase 1 后 |
|---|---|---:|---:|
| I 自实现 ReAct | 只传递显式 SkillSnapshot，不替换循环 | PASS | PASS |
| II Spring AI 只做协议/Schema | L2/L3 仍由 ToolExecutor 调度 | PASS | PASS |
| III Provider 显式映射 | 不改 Provider 路由 | PASS | PASS |
| IV 一个目录=一个 Agent；公共 Skill 市场是唯一共享例外 | 公共包只存一份，Agent 只用标准相对软链接显式安装 | PASS | PASS |
| V 审计 Day One | Tool 读取继续落审计；管理动作写结构化事件 | PASS | PASS |
| VI 沙箱与路径安全 | 只接受标准相对链接，真实路径仍限制在 `.oryxos` | PASS | PASS |
| VII 同步 + virtual thread | NIO、扫描、锁和 REST 均同步 | PASS | PASS |
| VIII AGENT.md 定义运行配置，Skill 关联状态外置 | AGENT.md 不写 Skill 名单；包、marker 与标准链接构成文件系统真相 | PASS | PASS |

**Gate result**: PASS。宪章 v2.0.0 已把公共 Skill 市场定义为唯一共享例外；本计划严格限制为 `.oryxos/skills` 公共包与 Agent 内标准相对软链接，不建立其它共享根、YAML/数据库关联或 Tool 旁路。PR 必须提供醒目的 `Governance Amendment / 治理修订` 区块说明该边界。

## Project Structure

### Documentation

```text
specs/012-skill-management/
├── checklists/requirements.md
├── governance-amendment.md
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/
    ├── internal-api.md
    ├── parser-manifest.md
    ├── rest-api.md
    └── skill-package.md
```

### Source Code

```text
oryxos-core/src/main/java/io/oryxos/core/
├── agent/
│   ├── AgentLifecycleService.java          # 创建后建链接；不写 AGENT.md skills/example
│   ├── AgentService.java                   # 请求租约覆盖 ReAct 与 session save
│   ├── PromptBuilder.java                  # 显式接收同一 SkillSnapshot
│   └── ToolExecutor.java                   # L2/L3 guard 后执行并写审计
├── context/
│   ├── ContextLoader.java                  # 删除 Profile.skills 正文注入，只渲染 snapshot
│   └── MarkdownFrontmatter.java            # 规范化、fence 定位与稳定解析错误
└── skill/
    ├── SkillMetadataReader.java             # YAML 1.2 等价安全解析与 manifest 校验
    ├── SkillManifestLimits.java             # activation/requires 有界限制
    ├── AgentSkillCatalog.java              # 扫描/校验 Agent 标准链接，构建 L1
    ├── AgentSkillCoordinator.java           # 请求租约
    ├── AgentSkillLockRegistry.java          # 单/多 Agent fair locks
    ├── PublicSkillCatalog.java              # 公共包三态、详情、内容验证
    ├── SkillAssociationService.java         # link/unlink/list
    ├── SkillGraphCoordinator.java           # 全局图谱锁 + 固定锁序
    ├── SkillManagementService.java          # 公共 import/toggle/delete/force-delete
    ├── SkillResourceAccessGuard.java        # 每次 L2/L3 重验 snapshot/link/containment
    ├── SkillLease.java
    ├── SkillPackageImporter.java
    ├── SkillInUseException.java
    ├── SkillStore.java
    └── SkillSnapshot.java

oryxos-web/src/main/java/io/oryxos/web/
├── common/ApiResponse.java                 # 允许安全的结构化错误 data
├── GlobalExceptionHandler.java             # 409 SkillInUse payload
└── controller/
    ├── SkillApiController.java              # 公共包管理
    ├── AgentSkillApiController.java         # Agent 关联管理
    └── dto/                                 # public/detail/association/delete DTO

oryxos-web/src/main/frontend/src/
├── App.vue                                  # 公共 Skill 页、Agent 创建/详情接线
├── api/skills.js                            # 保留 status/code/data 的错误对象
└── components/
    ├── AgentSkillsTab.vue                   # 已关联/可关联，不再管理私有包
    └── SkillManagementPanel.vue             # A→B 删除及公共生命周期管理
```

**Structure Decision**: 不新建 Maven 模块。将现有“公共 CRUD”和“Agent 私有渐进加载”合并为公共内容目录 + Agent 软链接投影；删除重复的 eager loading 与私有包 HTTP 语义，最大化复用已验证的解析、导入和租约代码。

## Phase 0: Research Decisions

详见 [research.md](./research.md)：

1. 软链接是唯一关联真相源，原始 target 必须精确等于 `../../../skills/<skill>`。
2. 公共包是真实目录；包内链接/特殊文件仍非法。
3. `Profile.skills` 仅兼容解析并告警，不参与加载；`ContextLoader` 删除全文注入。
4. 全局 disabled marker 位于公共包，单 Agent 停用等于 unlink。
5. 删除每次扫描全部 Agent；普通删除 409，force 在锁内重扫。
6. 锁序固定为全局 Skill 图谱锁 → Agent 名升序锁；请求持图谱读锁与单 Agent 读锁。
7. force 删除采用预检、固定锁序和同进程尽力补偿；本期不做持久化 journal、启动恢复或跨崩溃原子保证。
8. `SKILL.md` 先规范化再解析，使用 YAML 1.2 等价安全配置，并对 name/version/activation/requires 建立独立、稳定契约。
9. 公共包 REST 与 Agent 关联 REST 分离；409 返回结构化 Agent 列表。

## Phase 1: Design Outputs

- [data-model.md](./data-model.md)：公共包、manifest、软链接关联、快照、全局状态与锁内删除状态机。
- [contracts/skill-package.md](./contracts/skill-package.md)：公共包、标准链接和包内容安全契约。
- [contracts/parser-manifest.md](./contracts/parser-manifest.md)：frontmatter 解析步骤、字段 grammar、限额、告警和稳定错误。
- [contracts/rest-api.md](./contracts/rest-api.md)：公共包/Agent 关联 REST、409→force 流程和 DTO。
- [contracts/internal-api.md](./contracts/internal-api.md)：parser、catalog、association、graph lock、runtime snapshot 和生命周期边界。
- [quickstart.md](./quickstart.md)：导入、关联、渐进加载、全局禁用、普通/强删、链接攻击和并发验收。

## Implementation Strategy

### 1. 统一公共包领域模型

把 `SkillManagementService` 的安全导入、marker、descriptor、归档从 Agent 私有目录迁移到 `.oryxos/skills`；`PublicSkillCatalog` 只扫描真实公共包。收敛 `MarkdownFrontmatter`/`SkillMetadataReader`：先统一行尾、移除 BOM 和前导换行，再定位 fence，使用 YAML 1.2 等价安全解析，校验 name/version grammar、activation/requires 限额并输出稳定错误/legacy warning。统一 `SkillStore/SkillLoader/SkillRegistry/SkillService` 与 managed package 类型，避免两套状态并存。

### 2. 实现标准关联与渐进加载

新增 `SkillAssociationService`：创建精确相对链接、幂等识别标准链接、安全 unlink、列出 valid/invalid link。改造 `AgentSkillCatalog` 从链接解析公共包；L1 entry 使用 Agent 内链接入口。`AgentService` 在顶层请求入口取得 graph read + Agent read lease，构建一次 snapshot 并显式传给 PromptBuilder、ReActLoop 与 ToolExecutor，直到 session save 后释放。移除 `ContextLoader.appendSkills(Profile)` eager body 注入，`Profile.skills` 不再影响运行时。

每次指向 Agent Skill 入口或包内资源的 `read_file`/`shell` 都先过 `SkillResourceAccessGuard`：验证 Skill 属于本次 snapshot、入口仍是一层标准链接、资源最终位于该公共包且中间无链接逃逸、Agent 已显式授权对应 Tool；随后仍执行通用 SandboxChecker 和既有审计。失败作为带稳定 reason code 的 ToolResult 回填当前 ReAct，允许模型纠正或退出；不得自动改读其它路径、自动进入 L3、扩大权限或使其它 Skill/Agent 失效。

### 3. 改造 Agent 创建与兼容路径

创建请求的 `skills` 语义改为“创建后要建立的链接”。先验证全部公共包，再创建 Agent 与全部链接；失败回滚整个新 Agent。生成草稿不写 `skills:`；保存生成文件后建链接。脚手架不生成 `skills/example/SKILL.md`。旧 `AGENT.md skills:` 保持可解析但忽略并告警，不自动迁移。

### 4. 全局状态与删除

引入 fair `SkillGraphCoordinator`。关联/解除关联/禁用/启用/删除遵循固定锁序；普通删除扫描所有 Agent 并在有关联时零副作用返回 `SkillInUseException`。force 删除锁内重扫并预检全部标准链接，逐个 unlink 后原子归档公共包；同进程失败时尽力重建本操作已移除且路径仍为空的标准链接，然后返回可诊断错误供管理员重试。本期不写持久化 operation journal，不实现启动恢复，也不承诺进程崩溃时的跨路径原子性。

### 5. REST 与管理台

公共 Skill API 管理 import/list/detail/global toggle/delete；Agent API 只管理 link list/associate/dissociate。`ApiResponse`/异常处理支持安全结构化 409 data。管理台公共页执行 A→B 删除弹窗；Agent Skill tab 展示“已关联/可关联”，移除私有 ZIP、私有 toggle/delete。请求助手保留 `status/code/data`。

### 6. 验证与文档

测试 parser 矩阵、manifest grammar/限额、精确链接字面值、Agent 创建事务与无 example、工作区移动、L1/L2/L3、全局 disabled、多 Agent 锁序、force rescan/同进程补偿、409 payload 与前端确认流。同步 TechnicalSolution/AiProgrammingGuide/README 的运行机制与宪章 v2.0.0 市场例外；PR 描述必须加入醒目的治理修订区块。

## Compatibility and Migration

- 旧 `AGENT.md skills:` 不报解析错误，但不再产生关联或正文注入；启动/管理时一次性 WARN。
- 不自动把旧名单迁移成链接，避免未经管理员确认改变文件系统。
- Agent `skills/*.md` 和真实子目录保持 legacy/unmanaged；路径占用时关联返回 409，不覆盖。
- 现有反向 `/skills/{skill}/agents/{agent}` 可暂留兼容适配，但必须委托链接服务；新 UI 使用 Agent 子资源契约。
- 现有 GitHub 导入能力不作为本 Feature 的新增契约；若保留，必须落同一公共包验证/发布路径，不能形成旁路。
- 旧的合法 lowercase-kebab Skill 名仍合法；解析器扩展为规范 grammar 后不会破坏既有包。可选 `version` 缺失继续兼容，出现时必须通过安全 grammar。
- 无数据库、Profile schema 或 Tool schema 迁移。

## Verification Gates

1. parser 矩阵覆盖 CRLF/CR、BOM、前导空行、opening 行尾部、closing fence 尾随空白、缺 fence、坏 YAML、空正文、legacy warning，以及 name/version 边界和 activation/requires 确定性过滤/截断。
2. `readSymbolicLink` 精确等于标准 target；绝对/别名/越界/悬空/循环链接不进 L1。
3. Agent 创建时选中 Skill 会得到真实标准链接；任何失败不发布 Agent，且不存在自动生成的 `example`。
4. 首轮 prompt 只有已关联 enabled Skill 的 L1，`AGENT.md skills:` 和未命中正文标记均为 0；预算超限按名称顺序确定性省略并记录 omittedCount/WARN。
5. L2/L3 每次 Tool call 重验 snapshot、标准链接、包 containment、显式 Tool 权限、沙箱和审计；失败返回稳定 Tool error，不产生旁路读取。
6. disabled 链接保留但所有关联 Agent 下一请求不可发现；unlink 只影响单 Agent。
7. 普通删除返回排序完整 Agent 列表且文件零变化；force 在锁内纳入新关联并完成归档；故障注入覆盖第 N 个 unlink、归档失败和同进程补偿。
8. REST 覆盖 400/404/409/413/500、Agent collection GET 且无绝对路径/正文；前端只在 `SKILL_IN_USE` 时允许 force。
9. `mvn clean verify`、Boot E2E、`npm test -- --run`、`npm run build` 全绿。
10. PR 描述包含醒目的治理修订区块，并证明实现未越过宪章 v2.0.0 的市场例外边界。

## Risks and Mitigations

| 风险 | 缓解 |
|---|---|
| 多目录 force 删除不是文件系统事务 | 预检、固定锁序、同进程尽力补偿和可重试诊断；跨崩溃恢复明确延期 |
| 扫描与新关联竞态 | 全局图谱锁；force 锁内重扫 |
| 多 Agent 死锁 | Agent 名排序获取、反序释放；统一锁序 |
| 手工恶意链接 | 原始 target 精确比较 + NOFOLLOW 父链 + real-path containment |
| 两套旧实现继续并存 | 删除 eager path，公共/关联职责拆分，测试禁止 Profile.skills 生效 |
| 市场例外被泛化为任意共享机制 | 测试与 PR 独立检查公共根、标准链接、无 YAML/数据库关联、无 Tool 扩权 |

## Complexity Tracking

无宪章违规。公共 Skill 市场与标准软链接属于宪章 v2.0.0 Principle IV 明确定义的受控例外。
