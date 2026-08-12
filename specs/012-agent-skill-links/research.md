# Research: Agent Skill 软连接绑定与三级渐进加载

## D1：Agent 本地固定相对软连接是唯一绑定事实

**Decision**: `.oryxos/agents/<agent>/skills/<skill>` 必须精确指向
`../../../skills/<skill>`；Agent 与 Skill 名均为安全单目录段。frontmatter、Registry、生成草稿和
catalog 都不得作为运行时绑定来源。

**Rationale**: 单一文件系统事实同时表达能力边界和共享关系；固定相对目标可验证、可迁移，而且
Agent 从 `agents/<name>` 移到同深度 `archive/<name[-timestamp]>` 后仍有效。

**Alternatives considered**:

- frontmatter `skills:`：会形成第二真相源，已否决。
- 复制 Skill 内容到 Agent：更新漂移且浪费空间，已否决。
- 任意安全相对路径：语义虽可能等价，但不利于残留检测和一致性修复，已否决。
- 绝对链接：工作区迁移即失效，也扩大越界风险，已否决。

## D2：每次 LLM 调用执行三级渐进披露

**Decision**:

1. `ContextLoader` 每次只注入有效绑定的 name、description 和 Agent 本地绝对 `SKILL.md` 路径。
2. 模型需要时用现有 `read_file` 读取 `SKILL.md`，正文以 Tool Result 进入下一轮 ReAct history。
3. 正文提到的 references/templates/scripts 再经 `read_file`/`shell` 按需获取。

**Rationale**: 现有调用链 `ReActLoop` 每轮都执行 `PromptBuilder.build()`，后者每次调用
`ContextLoader.load()`；把扫描放这里即可保证“一次 provider chat 对应一次新目录快照”。

**Alternatives considered**:

- 每个用户请求只扫一次：同一 ReAct 中途修改无法生效，已否决。
- 预载全部正文：绑定越多 system prompt 越大，违反宪章 IV，已否决。
- 新增 `use_skill`：把上下文资源误建模成 Tool，已否决。
- 扫描所有安装 Skill：暴露未绑定能力，已否决。

## D3：目录构建只读 Skill frontmatter

**Decision**: 新增 `SkillMetadataReader`，只读取 `SKILL.md` 第二个 `---` 之前的 frontmatter；要求
name、description 和非空正文边界可验证，但目录构建不得把正文装入 `Skill`/prompt。完整正文只允许
通过工具读取。

**Rationale**: “不注入正文”之外，还应避免每轮为了目录发现构造完整正文对象；元数据读取与完整
Skill CRUD 解析职责分开更符合渐进披露。

**Alternatives considered**:

- 复用 `SkillLoader.deriveSkill()` 全文读取再丢弃 body：输出正确但不是真正元数据级加载，已否决。
- 缓存元数据：会让手工编辑在下一轮不生效，已否决。

## D4：core 内区分只读发现边界和写协调服务

**Decision**: `AgentSkillBindingReader.inspect(agent)` 返回稳定排序的 bindings + issues；
`AgentSkillBindingService` 实现它，并提供 bind、unbind、atomic replace、references、reconcileAll。
`ContextLoader` 只依赖 Reader，不依赖 `SkillRegistry` 或 CRUD。

**Rationale**: prompt 只需要实时只读视图；写操作、引用反查和锁策略不应渗入上下文组装。

**Alternatives considered**:

- Web Controller 直接操作链接：启动、CLI 和 prompt 无法复用，已否决。
- 放入 `oryxos-tool`：Skill 不是可执行 Tool，违反模块边界，已否决。
- 新建 Maven 模块：当前规模不需要，已否决。

## D5：坏绑定结构化报告、跳过但不自动修复

**Decision**: 统一分类 `DANGLING`、`ESCAPED`、`INVALID_TARGET`、`NAME_MISMATCH`、
`STALE_REFERENCE`。其中 stale 包括残留旧 frontmatter 或非有效 Agent 目录中的绑定；归档 Agent 的
合法链接不是 stale。prompt 只消费有效项，协调扫描不删除用户文件。

**Rationale**: 文件系统允许外部手工修改；安全默认应是“不注入 + 可观察”，而不是静默接受或擅自
破坏现场。

**Alternatives considered**:

- 遇一个坏链接阻断整个 Agent：合法能力也不可用，可用性差。
- 自动删除坏链接：可能删除运营者正在修复的文件。
- 只写普通日志：管理台无法呈现结构化问题。

## D6：旧 `skills:` 在启动时按单 Agent 原子迁移

**Decision**: `AgentSkillMigrationService` 在 Profile 扫描和 Watcher 启动前运行。它先验证全部 legacy
名称、安装实体和现有槽位；再写 AGENT.md 临时文件、创建并验证临时链接、原子移动链接，最后用
`ATOMIC_MOVE + REPLACE_EXISTING` 提交去掉顶层 `skills` 键的新 AGENT.md。任何异常回滚本次新增，
不支持原子移动的文件系统明确失败。

**Rationale**: AGENT.md 最后提交可保证不会出现“旧字段已删但链接缺失”；进程若在提交前崩溃，
下次把已存在合法链接当作幂等状态即可收敛。

**Alternatives considered**:

- 在 `ProfileLoader` 边解析边迁移：让纯解析器产生文件副作用，已否决。
- 先删 frontmatter 再建链接：崩溃会丢失能力，已否决。
- 非原子覆盖或部分成功：违反 clarify 结论，已否决。

## D7：绑定替换、迁移和 Skill 归档共用工作区临界区

**Decision**: 同一 `AgentSkillBindingService` 实例持有 workspace 级同步锁；bind/unbind/replace、legacy
迁移、Agent 归档和 Skill 归档都在该边界内先全量验证、记录变更、执行并在失败时回滚。当前保证同一
OryxOS 进程内不产生已确认成功的悬空状态。

**Rationale**: 只分别给 `SkillService.delete` 和 `bind` 加锁无法关闭查引用到移动目录之间的竞态。

**Alternatives considered**:

- 数据库绑定表/分布式锁：本阶段绑定真相在文件系统且是单实例，复杂度不必要。
- 逐项 replace 不回滚：中途 IO 失败会形成半套能力，已否决。

## D8：Skill 删除改为完整目录归档

**Decision**: 先扫描活跃与归档 Agent 引用；非空返回结构化引用并拒绝。无引用时把整个目录移动到
`.oryxos/archive/skills/<name>-<timestamp>/`，绝不覆盖已有归档，成功后再移出安装 Registry。

**Rationale**: 保留来源、正文和所有附属资源，符合项目对 Agent 删除同样采用归档的可追溯原则。

**Alternatives considered**:

- 物理递归删除：不可恢复，已被 clarify 否决。
- 级联解绑：影响多个 Agent，违反最小惊讶原则。
- 把归档留在 `.oryxos/skills/.archive`：容易被安装扫描误收录，已否决。

## D9：保留平铺 Agent 归档并预留 `archive/skills`

**Decision**: Agent 继续归档到 `.oryxos/archive/<agent[-timestamp]>`，保持链接相对深度；
`archive/skills` 作为 Skill 归档保留命名空间。Agent 名为 `skills` 时强制带时间戳。升级时若
`archive/skills/AGENT.md` 是旧归档 Agent，先无损改名再建立 Skill 归档根。

**Rationale**: 把 Agent 改到 `archive/agents/` 会多一层目录，使现有 `../../../skills/...` 链接失效。

**Alternatives considered**:

- 重构全部 Agent 归档并重写链接：范围大、升级风险高，已否决。
- 不处理保留名冲突：真实存在数据覆盖风险，已否决。

## D10：外部 catalog 与本机安装列表分离

**Decision**: `SkillCatalog` port 查询 name、description、visibility、source、installed；访问过滤由
adapter 负责，OryxOS 不保存 owner/scope 或实现 ACL。现有 `SkillService`/Registry 仍表示
`.oryxos/skills` 中的本机安装实体。作者模型只看到 catalog 结果与合法已安装集合的交集；未安装项
可展示但禁选，本阶段不自动联网安装。

**Rationale**: 公共/私有是候选列表属性，不应污染绑定或复制 Skill；外部协议尚未确定，以 core port
隔离可避免锁死某个服务。

**Alternatives considered**:

- 把 visibility/source 写进 Profile 或绑定索引：与能力绑定无关，已否决。
- OryxOS 自建 Skill ACL：当前核心阶段明确无认证/RBAC，已否决。
- 模型建议即自动下载：供应链和失败语义未定义，已否决。

## D11：作者建议使用瞬时 sidecar，不写 Agent 文件

**Decision**: 生成响应分别携带 files、requiredSkills、suggestedSkills、bindingSkills。模型建议必须
来自本次 catalog 候选；保存请求显式携带最终 `skillBindings`，后端再次验证后建立链接。新建/保存
拒绝 `AGENT.md skills:` 和任何 `skills/**` 普通文件。

**Rationale**: 草稿可以表达自动选择且允许管理台预览，但保存成功后唯一事实仍是软连接。

**Alternatives considered**:

- 模型继续写 frontmatter：直接违反宪章 IV。
- 生成隐藏 manifest 并落盘：形成第二持久化索引，已否决。
- 只支持用户手选：不符合 clarify 选定的“用户必选 + 模型补充”。

## D12：真实路径投影集中复用

**Decision**: core 提供 `RealPathBoundary.project(path)`：absolute+normalize 后，从目标向上用
`NOFOLLOW_LINKS` 找最近已存在节点，调用 `toRealPath()` 解析链接链，再拼回未存在尾段。白名单根也
投影一次，目标每次投影后用 `startsWith(canonicalRoot)` 比较。dangling、链接环和无法解析路径均拒绝。

**Rationale**: 默认 `Files.exists()` 会跟随链接，把 dangling link 错当不存在；纯
`normalize()+startsWith()` 又无法识别允许根中的外跳链接。

**Alternatives considered**:

- 禁止所有软连接：会阻断合法 Skill 绑定。
- 只解析最终目标：不存在写目标和父目录软连接仍可逃逸。
- 只修 `WhitelistSandbox`：Workspace API 与 Store 仍能绕过，已否决。

## D13：所有文件入口与目录树同步加固

**Decision**: `WhitelistSandbox`、`WorkspaceApiController`、`AgentStore`、`SkillStore` 复用真实路径
边界。工作区 tree 把软连接显示为 link 叶节点而不递归；Agent 文件浏览器拒绝通过
`agents/*/skills/**` 编辑共享内容，共享 Skill 修改走顶层管理入口。

**Rationale**: 当前 Workspace tree 会跟随链接递归，既可能越界，也可能因链接环无限递归；Store 的
lexical startsWith 也会沿现有恶意链接写到工作区外。

**Alternatives considered**:

- 只靠 Tool 沙箱：管理 API 和 Store 不经过它，覆盖不完整。
- 对链接递归但维护 visited set：仍重复展示共享内容，且让 Agent 文件入口绕过 Skill CRUD。

## D14：启动顺序用显式依赖表达

**Decision**: 装配顺序为：安装 Skill 加载 → 内置 Skill 播种 → 绑定服务 → legacy 迁移 → reconcile
报告 → Profile 扫描/调度 → AgentLifecycle/WorkspaceWatcher → ContextLoader/PromptBuilder/ReActLoop。
用 bean 参数依赖或 startup report 表达，不依赖源码声明顺序。

**Rationale**: legacy 可能引用内置 Skill；迁移必须在 Profile/Watcher 观察 Agent 前完成。

**Alternatives considered**:

- 靠 `@Bean` 方法排列：Spring 不保证语义足够清晰，测试也难固定。
- Watcher 启动后迁移：会产生重复注册和中间状态，已否决。

## D15：TOCTOU 威胁边界

**Decision**: 本阶段防御配置错误、手工残留和 Agent 发起的确定性链接逃逸；不承诺阻止同一 OS 用户
恶意进程在校验与 IO 之间替换链接。所有项目内 CRUD 仍通过共享锁和先校验后 IO 缩小窗口。

**Rationale**: Java 便携 NIO 没有能覆盖所有操作与平台的目录句柄式原子 API；
`SecureDirectoryStream` 也非普遍可用。

**Alternatives considered**:

- 声称完全消除本地 TOCTOU：无法验证，已否决。
- 引入本地守护进程/原生 syscall：超出本阶段范围。
