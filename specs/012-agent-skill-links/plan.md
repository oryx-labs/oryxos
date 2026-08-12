# Implementation Plan: Agent Skill 软连接绑定与三级渐进加载

**Branch**: `codex/agent-skill-progressive-loading` | **Date**: 2026-07-27 | **Spec**: [spec.md](spec.md)

**Input**: Issue #40 已批准方案及 2026-07-26 clarify 结果。

## Summary

把旧的 `AGENT.md skills:` + Skill 正文预载模型替换为单一文件系统绑定模型：已安装 Skill
统一存放在 `.oryxos/skills/<name>/`，每个 Agent 只通过自身 `skills/<name>` 的固定相对软连接
选择能力。每一次 LLM 调用都由 `ContextLoader` 重新扫描当前 Agent 的有效绑定，只把 name、
description 和 Agent 本地绝对 `SKILL.md` 路径放进 system prompt；模型需要时再用现有
`read_file` 读取正文，随后按正文指引继续读取 references/templates 或用 `shell` 执行 scripts。

本特性同时交付：旧 frontmatter 的单 Agent 原子迁移、公共/私有标签的外部 Skill 列表、用户必选
加作者模型自动补充的生成流程、绑定 CRUD 与一致性诊断、活跃/归档 Agent 引用保护、Skill 目录
归档，以及所有文件入口的软连接真实路径安全校验。

## Technical Context

**Language/Version**: Java 21；管理台 Vue 3 + Vite

**Primary Dependencies**: Spring Boot 3.x、Spring MVC、SnakeYAML、Java NIO；不新增运行时依赖

**Storage**: 文件系统（`.oryxos/skills/`、`.oryxos/agents/*/skills/`、
`.oryxos/archive/skills/`）；继续使用现有 SQLite 工具/LLM 审计，无新增表

**Testing**: JUnit 5、Mockito、Spring MockMvc、Maven Surefire/Failsafe；前端 `npm run build`

**Target Platform**: 支持 Java NIO 符号链接的 Linux/macOS 服务端；自定义相对或绝对
`oryxos.root` 均须工作

**Project Type**: Maven 多模块企业单体 + 内嵌 Vue 管理台

**Performance Goals**: 每次 LLM 调用仅扫描当前 Agent 的 B 个绑定，时间与临时对象复杂度 O(B)；
不扫描全 Skill 库、不读取附属资源、不缓存绑定

**Constraints**: 同步阻塞；绑定只有软连接一个真相源；正文不得常驻 system prompt；旧迁移按单 Agent
原子回滚；应用内 bind/归档在同一同步临界区；无 Skill ACL/RBAC；不得自动下载外部内容

**Scale/Scope**: 单工作区数十至数百 Agent、数百已安装 Skill；涉及 `oryxos-core`、
`oryxos-tool`、`oryxos-cli` 装配、`oryxos-web` API/管理台和运行时文档，不新增 Maven 模块

## Constitution Check

### 设计前门禁

- **I 自实现 ReAct Loop**: PASS。`ReActLoop` 不改执行权；其每轮既有
  `PromptBuilder.build()` 调用天然触发一次新的 Skill 元数据扫描。
- **II Spring AI 使用边界**: PASS。作者模型和运行 Agent 都继续走 `ProviderService`，不启用框架
  自动工具执行。
- **III Provider 显式映射**: PASS。无 Provider 发现或路由改动。
- **IV 目录 Agent + Skill 渐进披露**: PASS。软连接是唯一绑定事实，system prompt 仅有元数据；
  `SKILL.md` 与资源只经 `read_file`/`shell` 按需进入 ReAct。
- **V 审计 Day One**: PASS。读取 Skill 正文仍是普通 Tool 调用，继续写 `tool_invocations`；LLM 每轮
  继续写 `llm_calls`。
- **VI 真实路径沙箱**: PASS。Tool 文件访问与管理台工作区访问都纳入真实路径校验；合法工作区内
  Skill 链接放行，越界链拒绝。
- **VII 同步执行**: PASS。扫描、迁移、绑定和归档均使用同步 Java NIO，不引入异步模型。
- **VIII 目录配置与状态外置**: PASS。能力边界由 Agent 目录软连接表达，无数据库绑定表。

### Phase 1 设计后复核

- `Profile` 删除 `skills` 字段；启动迁移完成后 frontmatter 不再表达绑定。
- `SkillRegistry`/Skill 列表可缓存或展示已安装实体，但 `ContextLoader` 不从它读取绑定，避免第二
  真相源与陈旧元数据。
- 作者模型返回的建议 Skill 只存在于未保存草稿响应；保存时经后端重新校验并创建软连接，因此草稿
  列表不是持久绑定索引。
- 已安装 Skill 的 `visibility`/`source` 只服务查询和作者选择，不实现 ACL，也不改变统一存储路径。
- Skill “删除”在确认活跃与归档 Agent 均无引用后移动整个目录到
  `.oryxos/archive/skills/<name>-<timestamp>/`，不物理删除、不级联解绑。
- 旧配置迁移在 WorkspaceWatcher 和 Profile 扫描前执行；先全量校验，再暂存链接和新 AGENT.md，
  失败回滚本次新增项，单个 Agent 失败不影响其它 Agent；文件系统不支持原子移动时明确失败，
  不用非原子覆盖降级。
- 真实路径策略覆盖已存在目标、不存在写目标、白名单根、工作区浏览器和链接目录树不跟随。

结论：全部宪章门禁通过，无需复杂度豁免。

## Project Structure

### Documentation (this feature)

```text
specs/012-agent-skill-links/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── skill-bindings.md
├── checklists/
│   └── requirements.md
└── tasks.md                 # 下一阶段由 speckit-tasks 重建
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── agent/
│   ├── AgentLifecycleService.java
│   ├── AgentMarkdown.java
│   └── AgentStore.java
├── context/ContextLoader.java
├── fs/RealPathBoundary.java
├── profile/{Profile,ProfileLoader}.java
└── skill/
    ├── AgentSkillBindingReader.java
    ├── AgentSkillBinding.java
    ├── AgentSkillBindingService.java
    ├── AgentSkillMigrationService.java
    ├── SkillBindingIssue.java
    ├── SkillCatalog.java
    ├── SkillCatalogEntry.java
    ├── SkillLoader.java
    ├── SkillMetadataReader.java
    ├── SkillService.java
    └── SkillStore.java

oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java
oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java

oryxos-web/src/main/
├── java/io/oryxos/web/controller/
│   ├── AgentApiController.java
│   ├── SkillApiController.java
│   └── WorkspaceApiController.java
├── java/io/oryxos/web/controller/dto/
└── frontend/src/App.vue

oryxos-core/src/test/java/io/oryxos/core/{context,profile,skill,agent}/
oryxos-tool/src/test/java/io/oryxos/tool/sandbox/
oryxos-web/src/test/java/io/oryxos/web/controller/
```

**Structure Decision**: 绑定、迁移、目录元数据与 Skill catalog 契约都属于 Agent 上下文能力，放在
`oryxos-core`；实际 Tool 文件白名单仍由 `oryxos-tool` 实现；CLI 只负责严格启动顺序；Web 只做
HTTP/视图适配。现有模块边界足够，不新建模块。

## Runtime Design

### 三级渐进加载

1. **元数据常驻**：每次 `ReActLoop` 迭代调用 `PromptBuilder.build()`，进而调用
   `ContextLoader.load()`；它实时扫描当前 Agent 的 `skills/`，验证并按名称排序，只输出 name、
   description、Agent 本地绝对 `SKILL.md` 路径。`SkillMetadataReader` 仅读第二个 `---` 以前的
   frontmatter，不为目录构建读取正文。
2. **正文按需**：模型根据任务调用现有 `read_file(<local-link>/SKILL.md)`；正文作为已审计的 Tool
   Result 进入下一轮会话历史，不进入全局缓存或永久 system prompt。
3. **资源再按需**：正文引用的 references/templates/scripts 以 Skill 目录为基准，再经
   `read_file`/`shell` 逐项获取；未用资源永不进入上下文。

外部 Skill 列表只在 Agent 创建/管理阶段给作者模型候选；运行阶段绝不把未绑定 Skill 暴露给 Agent。

### 绑定、迁移与归档

1. `AgentSkillBindingService` 实现只读 `AgentSkillBindingReader`，使用固定相对目标
   `../../../skills/<name>`，负责 bind/unbind/atomic replace/inspect/references/reconcile，并以
   workspace 级同一同步临界区串行化绑定替换、Agent 归档、旧迁移与 Skill 归档。
2. `AgentSkillMigrationService` 启动时先校验某 Agent 的全部旧 `skills:` 与现有链接；校验成功后创建
   缺少的链接、用临时文件原子替换移除该键的 `AGENT.md`；异常删除本次新链接并保留原文。进程崩溃
   后重跑保持幂等并收敛。
3. 新建/编辑 API 拒绝重新写入 frontmatter `skills:`；`AgentStore.writeAll` 禁止普通文件占用保留的
   `skills/` 绑定命名空间。
4. Skill 归档扫描活跃 Agent 与平铺的历史 Agent 归档目录，跳过专用 `archive/skills/`；有引用即
   返回结构化完整列表，无引用才移动完整目录。`archive/skills` 是保留命名空间：Agent 名为
   `skills` 时归档到带时间戳的平铺目录；升级前若该路径已是旧归档 Agent，先无损改名再建 Skill
   归档根。归档区不参与已安装 Skill 扫描和 prompt。

### 作者模型与 Skill 列表

1. `SkillCatalog` 是外部候选查询 port，输出已过滤候选的 name、description、visibility、source、
   installed；visibility/source 只属于查询结果，不写进 `SKILL.md`、Profile 或数据库，OryxOS 不做
   ACL。现有 `SkillService`/Registry 仍只表示本机已安装实体。
2. Web 暴露独立 catalog 查询；作者模型只看到“外部 catalog 结果 ∩ 本机合法已安装 Skill”。未安装项
   可以展示但禁选，本阶段不得因模型建议而隐式联网安装。
3. 用户所选 Skill 作为 required 集合；作者模型可在同一候选列表中建议额外 Skill，但不能发明名称。
4. 生成响应用瞬时 sidecar 分开返回 files、required、suggested、bindingSkills；该列表只属于草稿，
   不写入任一 Agent 文件。
5. 保存时后端重新计算并验证最终集合仍来自可用 catalog 且已安装，然后原子同步 Agent 本地
   软连接。生成文件不得包含 `skills:` 或 `skills/**` 内容。

### 真实路径安全

1. core 提供无状态 `RealPathBoundary` 供 `WhitelistSandbox`、Workspace API、AgentStore 与 SkillStore
   复用；白名单根、已存在目标均解析 `toRealPath()` 后比较。
2. 不存在目标从目标向上用 `NOFOLLOW_LINKS` 找最近已存在节点，解析其真实路径再拼回尚不存在的
   后缀；最终/中间悬空链接、链接环或无法解析路径直接拒绝。
3. Agent Skill 绑定额外校验：链接必须相对、词法和真实目标均在公共 Skill 根、链接名/目录名/
   frontmatter name 一致。
4. `WorkspaceApiController` 的读、写、下载使用同一边界规则；目录树把软连接渲染为 link 叶节点而
   不递归跟随，避免越界遍历、内容重复和链接环。Agent 文件浏览器禁止经 `agents/*/skills/**`
   编辑共享 Skill，修改共享内容必须走顶层 Skill 管理入口。
5. Java 便携 NIO 无法彻底消除同一主机恶意进程在校验后换链的 TOCTOU；本阶段威胁模型覆盖配置、
   手工残留和 Agent 发起的确定性路径逃逸，不覆盖同 OS 用户恶意进程竞态。

## Delivery Order

1. 用失败测试固定绑定分类、三级 prompt、原子迁移和真实路径行为。
2. 完成 core 绑定/迁移/catalog 模型，移除 `Profile.skills`，再切换 `ContextLoader`。
3. 完成 Skill 归档与 Agent 生命周期/作者生成流程，随后以显式 bean 参数依赖接入启动顺序：已安装
   Skill 加载/内置播种 → 绑定服务 → legacy 迁移 → reconcile → Profile 扫描/调度 → Watcher。
4. 更新 Web contracts、DTO 和管理台；封住工作区树与文件入口的软连接问题。
5. 跑目标测试、前端构建、全量 `mvn test`/`mvn verify` 和 quickstart 场景。

## Complexity Tracking

无宪章违例。新增 `SkillCatalog` 是为了隔离“外部候选查询”和“已安装文件实体”，但它不保存 Agent
绑定、不引入网络客户端或数据库；新增迁移服务是一次性兼容旧格式所需，迁移完成后不会留并行模型。
