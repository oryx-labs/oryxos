# Implementation Plan: Agent 内 Skill 渐进式加载与生命周期管理

**Branch**: `012-skill-management` | **Date**: 2026-07-22 | **Spec**: [spec.md](./spec.md)

> `012-skill-management` 是本次 Spec Kit 的逻辑 feature id；当前 Git worktree 保持在 `main`，规划阶段未创建或切换 Git 分支。

**Input**: Feature specification from `specs/012-skill-management/spec.md`

## Summary

在“一个目录 = 一个 Agent”的边界内，引入兼容开放 Agent Skills 规范的 Agent 私有 Skill 包：每个包以 `SKILL.md` 为入口；每次顶层请求只把已启用 Skill 的名称、描述和读取位置作为 L1 注入 system context，命中后复用既有 `read_file` 读取 L2 正文，再按需读取参考资料或用 `shell` 运行 L3 脚本。

管理面向指定 Agent 提供本地 ZIP 导入、列表/详情、禁用/启用和归档式删除。导入通过同文件系统 staging、central-directory 安全校验、流式资源上限和 `ATOMIC_MOVE` 保证不出现半包；请求持有 Agent 级公平读租约，管理最终切换持有同一写租约，保证一轮 ReAct 内 L1/L2/L3 一致。全程不建立全局 Skill 库、不新增 `use_skill`、不自动扩大 Tool 权限、不绕过 ToolExecutor/沙箱/审计。

## Technical Context

**Language/Version**: Java 21；Vue 3 + Vite 6 管理台。

**Primary Dependencies**: Spring Boot 3.3.5 / Spring MVC、SnakeYAML（现有 frontmatter）、JDK NIO、Apache Commons Compress 1.28.0（只读 ZIP central-directory 与 Unix entry 类型）、既有 `read_file`/`shell` Tool。前端测试增加 dev-only Vitest + Vue Test Utils；不增加运行时前端库。

**Storage**: 文件系统是真相源：活动包位于 `.oryxos/agents/<agent>/skills/<skill>/`，禁用状态为包内 `.oryxos-disabled`，上传来源为 `.oryxos-origin.yml`，staging 位于 `.oryxos/.staging/skill-import/`，删除归档位于 `.oryxos/archive/.skills/`。不新增 SQLite 表。

**Testing**: JUnit 5 + Mockito、Commons Compress 构造的恶意 ZIP fixtures、Spring MockMvc、Spring Boot 临时工作区集成测试、现有 mock provider E2E；前端 Vitest + Vue Test Utils、Vite production build 和 quickstart 浏览器闭环。

**Target Platform**: 企业内网 Java 21 单 JAR；macOS/Linux 本地开发；K8s/服务器部署。目标文件系统必须支持 staging 到活动/归档目录的原子移动，不支持时安全失败。

**Project Type**: Maven 多模块 Web 应用。核心领域、发现、快照、导入和租约在 `oryxos-core`；Spring 配置绑定/装配在 `oryxos-cli`；REST/DTO/SPA 在 `oryxos-web`；默认配置和跨重启 E2E 在 `oryxos-boot`；`oryxos-tool` 不新增 Skill Tool。

**Performance Goals**: 默认上限内导入/启停在无活跃请求时 2 秒内完成；每个顶层请求不把 Skill 正文/资源载入 prompt，也不全文读取它们，但会为安全性做有界 frontmatter、全后代 `lstat`/size 和每文件最多 512 bytes magic-prefix 检查；L1 最多 12,000 字符并按名称确定性排序；上传与解包不把整包复制进 Java heap。

**Constraints**: 同步阻塞 + Java 21 virtual thread；一次顶层请求只建一次不可变 snapshot 并持有读租约；最终管理切换必须原子；不缓存正文；Profile 未声明 `read_file`/`shell` 时不自动扩权；旧版 `skills/*.md` 保持 legacy/unmanaged；REST 不泄露绝对路径或堆栈。

**Scale/Scope**: 单实例、多 Agent；默认每 Agent 最多 64 个受管 Skill；单 ZIP 10 MiB、解压 25 MiB、128 entries、L1 12,000 字符。本期不含远程 URL/Git/Marketplace、批量导入、签名、版本依赖、归档恢复 API、跨实例分布式锁或通用 shell 的逐路径强制隔离。

**Session Semantics**: disabled/deleted 阻止后续 L1 和由目录触发的 L2/L3 读取，但不改写既有 Session 消息或审计记录；新 Session 用于验证不可发现性。给 Message 增加 Skill provenance 和历史 redaction 属后续能力。

## Constitution Check

*GATE: Phase 0 前检查，并在 Phase 1 设计完成后复核。*

| 原则 | 设计影响 | Phase 0 前 | Phase 1 后 |
|---|---|---:|---:|
| I 自实现 ReAct | 只给现有 ReAct 显式传递请求 snapshot，不替换循环 | PASS | PASS |
| II Spring AI 只做协议/Schema | L2/L3 仍由自有 ReAct + ToolExecutor 调度；无自动 Tool 执行 | PASS | PASS |
| III Provider 显式映射 | 不改 Provider 路由或 Bean 发现 | PASS | PASS |
| IV 一个目录=一个 Agent；无全局 Skill/use_skill | Skill 严格位于所属 Agent；L1 属 ContextLoader；L2/L3 走既有 Tool | PASS | PASS |
| V 审计 Day One | L2/L3 继续写 tool_invocations；管理动作写结构化事件，不建执行旁路 | PASS | PASS |
| VI 应用沙箱 | ZIP 在发布前额外拒绝路径穿越、链接、特殊文件和资源耗尽；Tool 仍过 SandboxChecker | PASS | PASS |
| VII 同步 + virtual thread | ZIP、锁、扫描和 REST 均同步；不引入 Reactor/CompletableFuture | PASS | PASS |
| VIII 状态外置/模块边界 | 内容和状态落所属 Agent 文件目录；只保留进程内请求租约；不新建模块或循环依赖 | PASS | PASS |

**Gate result**: 通过。无需要豁免的宪法违规。

## Project Structure

### Documentation (this feature)

```text
specs/012-skill-management/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/
    ├── internal-api.md
    ├── rest-api.md
    └── skill-package.md
```

### Source Code (repository root)

```text
pom.xml                                             # Commons Compress version/dependency management

oryxos-core/src/main/java/io/oryxos/core/
├── agent/
│   ├── AgentMarkdown.java                         # 委托通用 frontmatter parser
│   ├── AgentName.java                             # Agent 名/目录/锁键唯一规则
│   ├── AgentService.java                          # 打开请求 lease、冻结 snapshot
│   ├── ReActLoop.java                             # 显式透传同一 snapshot
│   ├── PromptBuilder.java                         # 显式透传同一 snapshot
│   └── AgentLifecycleService.java                 # delete/saveFiles 共用写锁；新脚手架使用标准 Skill 包
├── context/
│   ├── ContextLoader.java                         # 渲染 L1，不读取正文
│   └── MarkdownFrontmatter.java                   # 有界流式 frontmatter 读取
└── skill/
    ├── AgentSkillCatalog.java                     # 扫描、三态、预算、snapshot
    ├── AgentSkillCoordinator.java                 # 请求 lease / 管理 mutation 入口
    ├── AgentSkillLockRegistry.java                # 每 Agent fair read/write lock
    ├── SkillDescriptor.java
    ├── SkillLease.java
    ├── SkillLimits.java
    ├── SkillManagementService.java                # list/get/import/toggle/delete + 管理日志
    ├── SkillMetadata.java
    ├── SkillMetadataReader.java
    ├── SkillPackageImporter.java                  # ZIP staging/校验/解包
    ├── SkillSnapshot.java
    ├── SkillSource.java
    └── SkillStatus.java

oryxos-cli/src/main/java/io/oryxos/cli/
├── OryxOsRuntime.java                             # 显式 Bean 装配
└── config/SkillProperties.java                    # oryxos.skills.* → SkillLimits

oryxos-web/src/main/java/io/oryxos/web/
├── GlobalExceptionHandler.java                    # 新增 409/413 安全映射
├── controller/
│   ├── AgentSkillApiController.java
│   ├── WorkspaceApiController.java                # 受管 Skill 写入共用写租约
│   └── dto/                                       # 沿用现有 Web DTO 包
│       ├── SetSkillEnabledRequest.java
│       ├── SkillDetailView.java
│       └── SkillSummaryView.java
└── error/
    ├── SkillConflictException.java
    └── SkillPackageTooLargeException.java

oryxos-web/src/main/frontend/src/
├── api/skills.js
├── components/
│   ├── AgentSkillsTab.vue
│   └── AgentSkillsTab.test.js
└── App.vue                                        # Agent 详情加入 Skill tab

oryxos-web/
├── pom.xml                                        # Maven 前端阶段增加 npm test gate
└── src/main/frontend/
    ├── package.json                               # test script + Vitest/Vue Test Utils
    └── package-lock.json                          # npm 锁文件同步更新

oryxos-boot/src/main/resources/
├── application.yml                                # multipart + oryxos.skills 默认值
└── logback-spring.xml                             # dev pattern 显示 %kvp
```

### Test Code

```text
oryxos-core/src/test/java/io/oryxos/core/
├── agent/AgentServiceTest.java
├── agent/ReActLoopTest.java
├── context/ContextLoaderTest.java
├── context/ProgressiveDisclosureTest.java
└── skill/
    ├── AgentSkillCatalogTest.java
    ├── AgentSkillCoordinatorTest.java
    ├── SkillManagementServiceTest.java
    ├── SkillMetadataReaderTest.java
    └── SkillPackageImporterTest.java

oryxos-web/src/test/java/io/oryxos/web/
├── GlobalExceptionHandlerTest.java
└── controller/AgentSkillApiControllerTest.java

oryxos-boot/src/test/java/io/oryxos/boot/
├── SkillManagementE2ETest.java
├── SkillProgressiveDisclosureE2ETest.java
└── SkillRestartRecoveryIT.java
```

**Structure Decision**: 不新建 Maven 模块。Skill 是 Agent 上下文和文件生命周期能力，放在 `oryxos-core`；Web 只适配 HTTP/UI；Tool 模块保持通用文件/脚本能力。Commons Compress 是唯一新增生产依赖，作用限定为 JDK ZIP API 缺失的 central-directory Unix 类型检测。

## Phase 0: Research Decisions

完整决策见 [research.md](./research.md)。已经收敛的结论：

1. 请求 snapshot 在 `AgentService.process` 冻结，显式传到 ContextLoader；同一请求持有读租约到会话保存结束。
2. L1 只含 name/description/entry；L2/L3 只走现有 `read_file`/`shell`，不自动扩权。
3. 受管形态只认 `skills/<name>/SKILL.md`；旧 `skills/*.md` 不迁移、不管理。
4. Commons Compress 1.28.0 读取 ZIP central directory；JDK NIO 创建普通文件和原子移动。
5. 同盘 staging、流式实际字节限制、严格路径规范化；原子移动不支持时不降级。
6. `.oryxos-disabled` 持久化管理员状态，invalid 每次扫描派生；删除归档到 `.oryxos/archive/.skills/`。
7. REST 使用 Agent 子资源 collection/member；UI 使用独立 Skill tab 组件和薄 API 模块。
8. 管理服务单点输出 SLF4J key-value 日志；结构校验不等同于内容可信。

## Phase 1: Design Outputs

- [data-model.md](./data-model.md)：文件布局、领域对象、状态转换、限制和租约一致性。
- [contracts/skill-package.md](./contracts/skill-package.md)：开放包格式、frontmatter、L2/L3 和 ZIP 安全规则。
- [contracts/rest-api.md](./contracts/rest-api.md)：五个 REST 资源操作、DTO、错误码和 OpenAPI 约束。
- [contracts/internal-api.md](./contracts/internal-api.md)：core API、运行时签名、装配和日志边界。
- [quickstart.md](./quickstart.md)：REST/UI/渐进加载/并发/恶意包/legacy 的验收流程。

## Implementation Strategy

### 1. 建立解析、模型与目录扫描基础

先抽取只负责 fence/有界文本的 `MarkdownFrontmatter`，让现有 Agent YAML 解析行为保持不变；Skill reader 单独使用 SafeConstructor/LoaderOptions。再实现 metadata 标准校验、三态 descriptor、预算和 `AgentSkillCatalog.snapshot`。扫描只读含 `SKILL.md` 或 OryxOS marker 的真实直接子目录，根 symlink 忽略并告警，单项失败隔离；平铺文件和无入口/marker 的 legacy 目录明确跳过。管理 list/get 在短读锁内完成整次扫描/统计。此阶段不接 ReAct，可独立完成 parser/catalog 单测。

### 2. 接入请求级渐进披露与租约

实现 fair lock registry/coordinator/lease，在 `AgentService.process` 用 try-with-resources 包住 ReAct 与会话保存。显式修改 ReActLoop、PromptBuilder、ContextLoader 签名，同一 snapshot 复用所有轮次；ContextLoader 只渲染 L1。通过 mock provider 证明首 prompt 无正文、只读取命中 Skill、未命中读取次数为 0；确认 ToolExecutor 审计路径未变。

### 3. 实现安全导入和文件生命周期

导入在写锁外流式保存与预校验，在写锁内以 `NOFOLLOW_LINKS` 重检 Agent/skills 父链、所有状态下的同名冲突、FileStore 和聚合预算，再 `ATOMIC_MOVE` 发布。删除同样验证 `archive/.skills/<agent>` 全父链无链接且仍在 workspace 内，再写归档元数据并原子移动。实现 reserved origin/disabled 文件、启用前重校验和归档清理。所有 staging 用 finally 清理，并在启动时清除超过 TTL 的孤儿。Agent 删除、`AgentLifecycleService.saveFiles`/`AgentStore.writeAll` 以及 Workspace API 的受管 Skill 写入全部接入同一锁并采用临时文件原子替换，避免既有 Agent files 端点成为旁路。

### 4. 暴露 REST 契约和安全错误

新增独立 Controller/DTO，把 `MultipartFile.getInputStream()` 交给 core，不把 Web 类型下沉。GlobalExceptionHandler 增加 409/413；领域消息只使用相对路径和稳定 reason code。补充 OpenAPI schema 和 MockMvc 攻击矩阵，断言所有失败无绝对路径/堆栈且无活动残留。

### 5. 增加 Agent 详情 Skill 管理页签

用 `api/skills.js` 封装五个操作及统一信封检查；member 操作使用服务端返回的安全 `directoryName`，不从 description/name 自行拼路径。`AgentSkillsTab.vue` 自管理列表、上传、collection error、逐行 busy、状态开关、确认删除和成功反馈；上传区明确提示“Skill 等同代码，只导入已审查来源”。App.vue 只增加 tab 接线。使用现有 token，自带 scoped 样式；服务端成功前不乐观改变行。用 Vitest 覆盖 FormData、重复提交、失败保留、信任提示和删除确认。

### 6. 完成恢复、并发、文档和全量门禁

Boot E2E 覆盖管理闭环和真实渐进读取；两次独立 Spring Context 验证 marker/归档跨重启；受控阻塞测试验证读写租约和 fair ordering。同步更新 `CLAUDE.md`、`docs/TechnicalSolution.md` §11、`docs/AiProgrammingGuide.md`、README/管理台说明中的 Skill 目录与信任边界，再执行单元、集成、Maven verify、前端测试与生产构建。

## Configuration Contract

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 11MB

oryxos:
  skills:
    staging-ttl: 24h
    package-limits:
      max-archive-size: 10MB
      max-expanded-size: 25MB
      max-file-size: 5MB
      max-skill-markdown-size: 256KB
      max-frontmatter-size: 64KB
      max-entries: 128
      max-depth: 8
      max-path-chars: 512
      max-expansion-ratio: 100
      max-yaml-nesting-depth: 8
    catalog:
      max-skills-per-agent: 64
      max-candidates-per-agent: 1024
      max-l1-chars: 12000
```

Spring multipart 是入口保护，core 限制是权威安全边界。启动时验证所有值和大小关系；非法配置必须点名失败，不静默改默认。

## Compatibility and Migration

- 不迁移现有 `skills/*.md`；它们仍可被 AGENT 正文显式引用。
- `AgentLoader` 明确执行既有设计不变量“目录名 = frontmatter/Profile name”；名称不一致的手工目录原本已无法被 ContextLoader 正确定位，本次改为清晰校验错误，用户需统一名称后再加载。
- 新建 Agent 的示例改为 `skills/example/SKILL.md`，不回写已有 Agent。
- 没有 `.oryxos-origin.yml` 的标准目录视为 `source=workspace`；无需导入登记即可在下一请求被扫描。
- invalid 包不阻断 Agent；修复后下一扫描自动恢复。若管理员已写 disabled marker，修复后仍保持 disabled。
- WorkspaceWatcher 继续只处理 Agent 一级注册；Skill 新鲜度来自每请求/每查询扫描，不新增内容 cache。
- 无数据库迁移、无 Profile schema 迁移、无 Tool schema 迁移。

## Security and Operational Boundaries

- 导入是管理员的显式信任动作，合法包默认 enabled。UI 和文档必须提示先审查内容；ZIP 校验只保证文件系统安全，不证明指令/脚本善意。
- disabled/invalid 从 OryxOS L1 和正常渐进加载中排除；本期不把通用 shell 变成逐路径强制访问控制器。
- disabled/deleted 不追溯清理旧 Session 里的 Skill Tool result；这是保留审计/对话完整性的明确边界，不宣称模型会“遗忘”。
- 进程内租约保证 Skill REST、`POST /agents/{name}/files`、管理台/Workspace 写入和 Agent 删除的一致性；管理员直接通过 shell/scp 改工作区属于旁路，无法等待当前请求，但下一扫描必须检测 invalid/变化。
- staging 与目标不在同一 FileStore或目标不支持原子移动时，导入/删除安全失败并保留活动状态；不降级复制。
- 管理日志不记录包内容、绝对路径或 secrets；REST 500 只返回通用消息。

## Verification Gates

1. `SkillMetadataReaderTest` 证明 frontmatter 在第二个 fence 停止，并覆盖大小/格式/标准字段。
2. `ContextLoaderTest`/`ProgressiveDisclosureTest` 证明 L1 无正文/L3，legacy 不被纳入。
3. mock provider E2E 证明只加载命中的 L2/L3，且仍写 tool audit。
4. 导入测试覆盖 Zip Slip、absolute/drive/UNC、NFC/case duplicate、symlink/special/encrypted、安全 YAML、binary/archive magic、zip bomb 和原子失败；workspace catalog 另覆盖根/入口/resource symlink 不跟随，delete 覆盖 archive 父链 symlink 拒绝。
5. 并发测试证明当前请求可完成、写者不饥饿、下一请求看到新状态。
6. REST 测试覆盖 400/404/409/413/500 安全信封，所有失败零活动残留。
7. restart IT 证明 enabled/disabled/invalid 派生和归档跨上下文一致。
8. 前端组件测试与人工 quickstart 完成导入→禁用→启用→删除。
9. `mvn clean verify`、integration tests、`npm test -- --run`、`npm run build` 全绿。

## Risks and Mitigations

| 风险 | 缓解 |
|---|---|
| 长 ReAct 持读锁导致管理操作等待 | fair lock；ZIP 预校验在写锁外；UI 显示 busy；后续如有数据再考虑版本目录 |
| NFS/特殊卷不支持原子移动 | 启动/首次操作明确检测并安全失败；不伪装原子性 |
| 恶意 ZIP 伪造 size 或链接类型 | central-directory 类型检查 + 实际流式字节计数 + NOFOLLOW_LINKS post-scan |
| 手工编辑绕过 API | 每请求扫描、单项 invalid 隔离；Workspace API 接同一锁；文档标注进程外旁路 |
| L1 目录膨胀 | 导入/启用前聚合预算校验；人工超限按名称确定性完整项截断并 WARN |
| Skill 指令恶意使用已有 Tool | 默认最小 Profile 权限、明确导入信任提示；allowed-tools 不扩权；执行仍过 ToolExecutor/沙箱/审计 |

## Complexity Tracking

无宪法违规。新增 Apache Commons Compress 是为了满足 JDK 公共 ZIP API 无法识别 Unix symlink/special entry 的安全缺口；作用域限定在 `oryxos-core` 导入器，不形成新模块或通用归档框架。

## Agent Context Update

本 Spec Kit checkout 不包含 `update-agent-context` 脚本，Phase 1 无可执行的自动上下文更新步骤。设计没有引入新的编程语言、框架或持久化技术；实施时按“文档同步”步骤更新项目指南，不手工伪造脚本产物。
