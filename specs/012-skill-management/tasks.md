# Tasks: 公共 Skill 渐进式加载、关联与生命周期管理

**Input**: Design documents from `specs/012-skill-management/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`

**Tests**: 本 Feature 的 spec 明确要求 parser、安全、并发、REST、前端与端到端自动化验收，因此各 Story 均包含先写失败测试的任务。

**Organization**: 任务按 User Story 分组；共享文件系统、安全值对象与日志骨架位于 Setup/Foundational，Story 内按测试 → 模型/服务 → 接口/集成排序。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可在不同文件上并行，且不依赖同阶段未完成任务
- **[Story]**: 对应 `spec.md` 的 US1–US5
- 每项任务均包含具体文件路径；新文件使用计划中的最终路径

---

## Phase 1: Setup（共享目录与配置）

**Purpose**: 建立公共 Skill 根、暂存区、归档区和统一安全预算；明确不创建 `.operations/skills`。

- [x] T001 [P] 在 `oryxos-cli/src/main/java/io/oryxos/cli/command/Workspace.java` 初始化 `.oryxos/skills`、`.oryxos/.staging/skill-import` 与 `.oryxos/archive/.skills` 真实目录，并以 `NOFOLLOW_LINKS` 拒绝父链软链接且不创建 operation journal 目录
- [x] T002 [P] 在 `oryxos-cli/src/main/java/io/oryxos/cli/config/SkillProperties.java` 配置 ZIP、展开量、单文件、frontmatter、YAML、L1 budget 与 staging TTL 的安全默认值和启动校验
- [x] T003 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageTestSupport.java` 增加公共包、标准相对链接、invalid link、disabled marker、归档、工作区整体移动和故障注入夹具
- [x] T004 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillLimits.java`、`oryxos-core/src/test/java/io/oryxos/core/skill/SkillLimitsTest.java` 与 `oryxos-cli/src/test/java/io/oryxos/cli/config/SkillPropertiesTest.java` 收敛配置对象并锁定正数、包含关系和 12,000 字符 L1 默认预算

---

## Phase 2: Foundational（阻塞所有 Story）

**Purpose**: 建立公共身份、三态、路径存储和单次领域事件基础，供运行时和 CRUD 共用。

**⚠️ CRITICAL**: 本阶段未完成前不得开始 Story 实现。

- [x] T005 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillValueObjectsTest.java` 为 SkillName/SkillVersion grammar、NFC/大小写冲突键、三态、来源、不可变集合与安全错误消息编写失败测试
- [x] T006 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillName.java`、`SkillVersion.java`、`SkillStatus.java`、`SkillSource.java`、`SkillValidationCode.java`、`SkillValidationError.java` 与 `FilesystemEntryNames.java` 实现统一值对象和稳定 reason code
- [x] T007 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillStoreTest.java` 覆盖公共根同名任意路径冲突、`NOFOLLOW_LINKS` containment、同 FileStore 原子移动、保留 marker 与安全相对路径
- [x] T008 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillStore.java`、`SkillOrigin.java` 与 `ArchivedSkill.java` 收敛公共根、staging、marker、origin 和 archive 的原子文件操作，不包含 Agent 私有存储或 operation journal
- [x] T009 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillDescriptorTest.java` 编写 PublicSkillDescriptor/SkillAssociation/DeleteResult 的状态推导、排序、defensive copy 与相对路径脱敏测试
- [x] T010 在 `oryxos-core/src/main/java/io/oryxos/core/skill/PublicSkillDescriptor.java`、`SkillAssociation.java`、`LinkStatus.java` 与 `DeleteResult.java` 实现公共包和关联视图值对象
- [x] T011 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementLoggingTest.java` 建立每次 service mutation 恰一条事件、rejected/failed reason code 和正文/绝对路径/API key 脱敏测试
- [x] T012 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementEventLogger.java` 实现 `skill.management` 结构化事件的统一入口，禁止 Controller 重复记录领域动作
- [x] T013 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 先装配共享 SkillLimits、SkillStore 与 SkillManagementEventLogger，删除指向 Agent 私有 Skill 根的重复实例
- [x] T014 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillFoundationArchitectureTest.java` 断言 Skill 状态不新增 SQLite 表、公共根唯一且没有 `use_skill`/ToolRegistry/AGENT.md 关联真相源

**Checkpoint**: 公共文件系统和值对象基础完成，所有 Story 可以按依赖顺序推进。

---

## Phase 3: User Story 1 — 用到时才加载 Skill（Priority: P1）🎯 MVP

**Goal**: 从 Agent 标准软链接构建固定 L1 snapshot，只在模型显式调用既有 Tool 时读取命中的 L2/L3，并完整执行 snapshot/link/containment/权限/沙箱/审计门禁。

**Independent Test**: 为一个 Agent 建立两个带唯一标记的公共 Skill 链接；首轮 prompt 只含两个 Skill 的 name/description/entry，只有命中 Skill 的 L2/L3 产生读取与审计，未命中 Skill 文件读取次数为 0。

### Tests for User Story 1

- [x] T015 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/MarkdownFrontmatterTest.java` 编写 LF/CRLF/CR、BOM、前导空行、opening 行尾兼容内容、closing 尾随空白、缺 fence、opening 无换行、非法 UTF-8 与 EmptyPrompt 解析矩阵
- [x] T016 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManifestParserTest.java` 编写 name 1/64、version 1/32、breakout、可选 version、description、未知字段与稳定错误分类测试
- [x] T017 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManifestYamlSecurityTest.java` 覆盖 YAML 1.2 `yes/no/on/off` 字符串语义、custom tag、duplicate key、alias、嵌套和 code-point 硬限制
- [x] T018 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManifestLimitsTest.java` 覆盖 activation 20/20/5/10、短 keyword/tag、setup_marker 256 bytes/`..`、requires.skills 10 项、确定性截断与单次安全 WARN
- [x] T019 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillLegacyManifestTest.java` 覆盖 `metadata.openclaw.requires` 只 WARN、不填充顶层 requires、不泄露原值且不阻断其它字段
- [x] T020 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/PublicSkillCatalogTest.java` 覆盖公共根直接候选、enabled/disabled/invalid、坏项隔离、legacy 忽略、资源统计、修复后恢复和绝对路径脱敏
- [x] T021 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillAssociationServiceTest.java` 覆盖 target 逐字等于 `../../../skills/<skill>`、相同链接幂等、list/findLinkedAgents 与 unlink 只删链接 inode
- [x] T022 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillAssociationSecurityTest.java` 覆盖绝对/不同层级/越界/悬空/循环链接、名称不一致、普通文件、真实目录和非标准链接占位，断言不跟随、不覆盖、不误删
- [x] T023 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillWorkspaceMoveTest.java` 创建标准链接后整体移动 `.oryxos` 工作区，断言 raw target 不变且公共包仍可发现
- [x] T024 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillCatalogTest.java` 覆盖只扫描 Agent `skills/` 直接软链接、disabled/invalid 排除、旧 `skills/*.md`/真实目录 unmanaged 与坏项隔离
- [x] T025 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillSnapshotTest.java` 覆盖 enabled+valid 选择、name 排序、12,000 字符预算、整条目确定性省略、omittedCount/WARN 与不可变性
- [x] T026 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderTest.java` 断言 L1 只含 name/description/Agent entry，不含正文、resource、origin、marker、allowed-tools、activation 或 requires
- [x] T027 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentLoaderTest.java` 与 `PromptBuilderTest.java` 断言旧 `AGENT.md skills:` 只兼容解析并去重 WARN，不产生关联或 eager 正文注入
- [x] T028 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillGraphCoordinatorTest.java` 覆盖 fair graph lock、graph→Agent 名升序锁、逆序释放、异常释放和锁对象不删除
- [x] T029 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillCoordinatorTest.java` 覆盖请求持 graph read+Agent read 至 ReAct/session save 完成、管理写等待和 writer 排队后新读者不持续插队
- [x] T030 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillResourceAccessGuardTest.java` 覆盖 `SKILL_NOT_IN_SNAPSHOT`、链接换靶、包内链接/逃逸、资源消失、无 read_file/shell 权限和稳定安全 Tool error
- [x] T031 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/ToolExecutorTest.java` 验证 Skill L2/L3 先过 guard、再过 SandboxChecker、成功/失败都写 `tool_invocations`，且不自动改读公共绝对路径或触发 L3
- [x] T032 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentServiceTest.java` 与 `ReActLoopTest.java` 断言一次顶层请求只构建并复用一个 SkillSnapshot，lease 覆盖异常路径和 session save
- [x] T033 [US1] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillProgressiveDisclosureE2ETest.java` 先写双 Skill 唯一标记端到端失败测试，断言首轮正文为 0、仅命中 L2/L3、未命中读取为 0、无 `use_skill` 且审计完整

### Implementation for User Story 1

- [x] T034 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/context/MarkdownFrontmatter.java` 按 parser contract 实现行尾归一化、BOM、前导换行、opening 前三字符、逐行 closing 定位、prompt 起点和有界 UTF-8 读取
- [x] T035 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManifest.java`、`ActivationCriteria.java`、`GatingRequirements.java`、`SkillManifestLimits.java` 与 `ParsedSkill.java` 实现受限 manifest 值对象和确定性 `enforceLimits()`
- [x] T036 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManifestParser.java`、`SkillMetadataReader.java`、`SkillMetadata.java` 与 `SkillValidationCode.java` 实现 YAML 1.2 等价 safe resolver、字段映射、legacy/limit WARN、name/version 校验和稳定错误
- [x] T037 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillContentValidator.java` 统一手工公共包、导入包和重新启用使用同一 parser/manifest/资源校验入口
- [x] T038 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/PublicSkillCatalog.java` 与 `PublicSkillDescriptor.java` 实现公共包三态扫描、单项 invalid 隔离、相对资源统计和无正文缓存
- [x] T039 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillAssociationService.java`、`SkillAssociation.java` 与 `LinkStatus.java` 实现标准 target、父链 NOFOLLOW、临时链接+原子发布、幂等 associate、全 Agent 实时扫描和安全 unlink
- [x] T040 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillCatalog.java` 改为只解析 Agent 标准链接到 PublicSkillCatalog，invalid link 仅用于管理展示且绝不进入 L1
- [x] T041 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillSnapshot.java` 与 `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java` 实现确定性 L1 snapshot/预算渲染并删除 `Profile.skills → SkillRegistry` 全文注入
- [x] T042 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillGraphCoordinator.java` 与 `AgentSkillLockRegistry.java` 实现 fair graph lock、规范 Agent 锁和排序多锁 API
- [x] T043 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillCoordinator.java` 与 `SkillLease.java` 实现 graph read→Agent read 取 snapshot、请求期持锁和幂等释放
- [x] T044 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillResourceAccessGuard.java` 与 `GuardedSkillResource.java` 实现 snapshot membership、标准链接重验、单层跟随、包 containment、Tool 权限和稳定拒绝结果
- [x] T045 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/ToolExecutionContext.java` 与 `ToolExecutor.java` 传递当前 SkillSnapshot，在既有 Tool 前调用 guard，并保持 SandboxChecker 与审计顺序
- [x] T046 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java`、`PromptBuilder.java` 与 `ReActLoop.java` 显式传递同一 snapshot，用 try-with-resources 让 lease 覆盖 profile、ReAct、session save 和异常清理
- [x] T047 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLoader.java` 与 `oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java` 保留旧 skills 输入兼容但停止运行时作用，并实现一次性安全 WARN
- [x] T048 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 将 AgentService、ContextLoader、ToolExecutor、catalog、association、graph coordinator 与 guard 装配为同一实例图并移除生产 eager SkillRegistry 路径
- [x] T049 [US1] 运行并修复 `oryxos-core/src/test/java/io/oryxos/core/skill/`、`oryxos-core/src/test/java/io/oryxos/core/agent/` 与 `oryxos-boot/src/test/java/io/oryxos/boot/SkillProgressiveDisclosureE2ETest.java`，使 US1 parser/link/L1/L2/L3/lease 全部通过

**Checkpoint**: US1 可独立演示；公共包可手工放置并通过标准链接按需加载，不需要导入或管理台。

---

## Phase 4: User Story 2 — 安全导入一个 Skill（Priority: P1）

**Goal**: 通过 multipart ZIP 完成暂存、完整安全校验和原子发布，并提供公共列表/详情与 Agent 关联 REST。

**Independent Test**: 上传合法包后关联 Agent 并无需重启即可发现；重名、缺入口、路径穿越、链接、特殊文件和超限包全部拒绝且 staging/活动区零残留。

### Tests for User Story 2

- [x] T050 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterTest.java` 覆盖 ZIP root/单 wrapper 合法 shape、manifest 身份、origin 清洗、默认 enabled 与所有分支 staging 清理
- [x] T051 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterPathSecurityTest.java` 覆盖 absolute/drive/UNC/backslash/NUL/dot segments、NFC/大小写重复和 wrapper 名称不一致
- [x] T052 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterEntrySecurityTest.java` 覆盖 symlink/hardlink、device/FIFO/socket、加密/不支持方法、嵌套归档/class/本机二进制
- [x] T053 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterLimitsTest.java` 覆盖 ZIP/展开总量/单文件/SKILL.md/frontmatter/entries/depth/path/ratio、伪造 header size 与 413 分类
- [x] T054 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementServiceTest.java` 覆盖锁外 prepare、graph write 内同名/FileStore 重检、ATOMIC_MOVE 发布、默认 enabled 和失败零副作用
- [x] T055 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/SkillApiControllerTest.java` 编写 public multipart import、GET list/detail、400/404/409/413/500、相对路径和不返回正文的契约测试
- [x] T056 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillApiControllerTest.java` 编写 canonical GET/PUT/DELETE、associate 幂等、invalid 400、占位 409 和 disabled discoverable=false 契约测试
- [x] T057 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/GlobalExceptionHandlerTest.java` 覆盖 Skill import/association 的统一信封、typed data 白名单与绝对路径/堆栈/正文脱敏
- [x] T058 [US2] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillManagementE2ETest.java` 先写“上传→关联→下一请求发现”和恶意/重名包零残留端到端失败测试

### Implementation for User Story 2

- [x] T059 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillPackageImporter.java` 与 `PreparedSkill.java` 实现有界 central-directory 检查、实际字节解压、safe staging、两种包 shape 和完整内容校验
- [x] T060 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现 `importZip` 的 prepare→锁内重检→原子发布→finally cleanup 和单事件日志
- [x] T061 [P] [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/PublicSkillSummaryView.java`、`PublicSkillDetailView.java`、`AgentSkillAssociationView.java`、`SkillUploadRequest.java` 与 `SkillView.java` 实现公共/关联安全 DTO 映射
- [x] T062 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillApiController.java` 实现 `POST/GET /api/v1/skills` multipart import、列表和详情，并确保浏览器 boundary 由 multipart 自动生成
- [x] T063 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentSkillApiController.java` 实现 `GET /agents/{agent}/skills` 与 canonical PUT/DELETE 关联资源，所有 mutation 委托同一 SkillAssociationService
- [x] T064 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/GlobalExceptionHandler.java` 与 `common/ApiResponse.java` 映射 invalid=400、missing=404、conflict=409、too-large=413、I/O=500 并仅返回安全 typed data
- [x] T065 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillAssociationApiController.java` 将旧逆向接口标记 deprecated 并委托 canonical service，彻底移除写 `AGENT.md` 的关联行为
- [x] T066 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillApiController.java` 与 `oryxos-web/src/main/java/io/oryxos/web/skill/GithubFolderFetcher.java` 让既有 GitHub 兼容入口复用同一 prepare/validate/publish 管线且不扩展远程协议
- [x] T067 [US2] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 接入公共 management/import 服务并运行 `oryxos-boot/src/test/java/io/oryxos/boot/SkillManagementE2ETest.java` 完成 US2 验收

**Checkpoint**: US1+US2 构成可通过 REST 使用的核心市场 MVP。

---

## Phase 5: User Story 3 — 禁用与重新启用 Skill（Priority: P2）

**Goal**: 使用公共包 `.oryxos-disabled` 实现全局启停，链接保留、状态跨重启、重新启用前完整复验；单 Agent 停用只 unlink。

**Independent Test**: 同一 Skill 关联两个 Agent 后禁用并重启，两边新请求均不可发现且链接存在；重新启用后恢复，解除一个 Agent 只影响该 Agent，旧 Session 不被改写。

### Tests for User Story 3

- [x] T068 [P] [US3] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillGlobalStateTest.java` 覆盖 marker 原子创建/移除、启停幂等、disabled 链接保留、invalid enable 保持禁用和两个 Agent snapshot 同步变化
- [x] T069 [P] [US3] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillGlobalStateConcurrencyTest.java` 覆盖当前请求持 lease 时 disable 等待、释放后下一请求生效和 writer 公平性
- [x] T070 [P] [US3] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/SkillApiControllerTest.java` 增加公共 PUT enabled boolean、404/400、最新详情与 Agent 无独立 toggle 的契约测试
- [x] T071 [US3] 将 `oryxos-boot/src/test/java/io/oryxos/boot/SkillRestartRecoveryIT.java` 替换为 `oryxos-boot/src/test/java/io/oryxos/boot/SkillGlobalStateRestartIT.java`，只验收 disabled marker 跨重启、重新启用多 Agent、新 Session 不发现和旧 Session 不改写

### Implementation for User Story 3

- [x] T072 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现 graph write 内全局 disable/enable、marker 原子操作、enable 完整复验、幂等语义和单事件日志
- [x] T073 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/PublicSkillCatalog.java` 与 `AgentSkillCatalog.java` 统一 INVALID 优先、configuredEnabled 和 discoverable 派生，保证禁用只影响下一 snapshot
- [x] T074 [US3] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillApiController.java` 与 `dto/SetSkillEnabledRequest.java` 实现 `PUT /api/v1/skills/{skillName}` 并返回最新公共详情
- [x] T075 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillAssociationService.java` 与 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentSkillApiController.java` 固化“单 Agent 停用=unlink”且不创建 Agent 级 marker
- [x] T076 [US3] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 确保重启直接扫描公共 marker/链接，不依赖 WatchService、缓存或恢复任务，并完成 US3 测试

**Checkpoint**: 全局启停和单 Agent 解除关联可以独立验收。

---

## Phase 6: User Story 4 — 删除并留痕（Priority: P2）

**Goal**: 普通删除 O(Agents) 扫描并在使用中返回 typed 409；force 锁内重扫、排序加锁、unlink 后归档，并只提供同进程尽力补偿。

**Independent Test**: 两个 Agent 关联同一 Skill；普通删除返回完整排序列表且文件零变化，force 后标准链接和活动包为 0、归档完整；不存在项 404，故障可诊断并可重试。

### Tests for User Story 4

- [x] T077 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillDeleteConflictTest.java` 覆盖每次遍历全部真实 Agent、排序去重、无反向 cache、非标准链接不计关联、`SKILL_IN_USE` 和零副作用
- [x] T078 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillArchiveSecurityTest.java` 覆盖无关联普通删除、enabled/disabled/invalid 完整归档、archive metadata、父链链接和跨 FileStore/atomic move 失败
- [x] T079 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillForceDeleteConcurrencyTest.java` 覆盖 409 后新增关联、force 锁内重扫、graph writer 阻断新关联、多 Agent 排序锁和当前请求完成后再 unlink
- [x] T080 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillForceDeleteCompensationTest.java` 对第 N 个 unlink 与 archive move 注入同进程异常，断言只重建本操作删除且 path 为空的标准链接、不覆盖外部占位、错误可诊断且重试重扫
- [x] T081 [P] [US4] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/SkillApiControllerTest.java` 增加 normal DELETE typed 409、`force=true`、实际 affectedAgents、404/500 脱敏和客户端旧 Agent 列表不参与执行测试
- [x] T082 [US4] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillForceDeleteIT.java` 先写普通→强删端到端失败测试，断言无 journal/启动恢复目录或 ready 阻塞逻辑

### Implementation for User Story 4

- [x] T083 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillInUseException.java` 与 `DeleteResult.java` 实现 typed conflict/result、排序 linked/affectedAgents 与 `SKILL_IN_USE` reason code
- [x] T084 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillAssociationService.java` 实现 graph write 内 O(Agents) `findLinkedAgents`，禁止反向索引、缓存和客户端列表输入
- [x] T085 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现 normal delete 的锁内全扫/冲突零副作用与无关联原子归档
- [x] T086 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现 force 的锁内重扫→Agent 排序写锁→全链接预检→unlink→ATOMIC_MOVE archive→同进程尽力补偿，不引入 journal/recovery 类型
- [x] T087 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/ArchivedSkill.java` 与 `SkillOrigin.java` 安全序列化 source、forced、deletedAt、affectedAgents 和相对原路径，不用用户值拼归档目录
- [x] T088 [US4] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/DeleteSkillResultView.java`、`SkillInUseErrorView.java`、`SkillApiController.java` 与 `GlobalExceptionHandler.java` 实现 normal/force DELETE 和 typed 409/500 响应
- [x] T089 [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementLoggingTest.java` 完成 delete/force success/rejected/failed 单事件断言，并运行 `oryxos-boot/src/test/java/io/oryxos/boot/SkillForceDeleteIT.java` 完成 US4 验收

**Checkpoint**: 后端公共 Skill 生命周期 CRUD 完整；没有持久化 force-delete journal 或启动恢复子系统。

---

## Phase 7: User Story 5 — 在管理台完成 Skill 管理（Priority: P3）

**Goal**: 管理台完成公共导入/详情/启停/A→B 删除、Agent 实际关联管理，以及创建 Agent 时事务性建立真实链接且无 example/YAML 名单。

**Independent Test**: 仅通过管理台完成“导入→创建 Agent 选择多个 Skill→详情确认链接→禁用→启用→普通删除冲突→二次确认强删”；页面与 REST/文件系统一致，任一步失败无半成品 Agent。

### Tests for User Story 5

- [x] T090 [P] [US5] 在 `oryxos-web/src/main/frontend/src/api/skills.test.js` 覆盖 multipart、公共 list/detail/toggle、canonical association、normal/force delete 和保留 status/code/message/data 的 ApiError
- [x] T091 [P] [US5] 在 `oryxos-web/src/main/frontend/src/components/AgentSkillsTab.test.js` 覆盖实际 linked/available 合并、invalid link、associate/unlink loading、防重复提交、失败保留行和无私有 ZIP/toggle/delete
- [x] T092 [P] [US5] 在 `oryxos-web/src/main/frontend/src/components/SkillManagementPanel.test.js` 覆盖公共导入/详情/启停、第一次只 normal delete、仅 SKILL_IN_USE 弹窗列 Agent、取消不 force、二次确认后刷新
- [x] T093 [P] [US5] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentLifecycleServiceTest.java` 覆盖 create skills 全量预校验、多个标准链接、任一步失败整体回滚、AGENT.md 无 skills、无 `skills/example` 和删除 Agent 不删公共包
- [x] T094 [P] [US5] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentApiControllerTest.java` 覆盖直接创建与生成后保存都建立真实链接、详情从文件系统派生、非法 Skill/占位失败且无半个 Agent
- [x] T095 [P] [US5] 在 `oryxos-web/src/main/frontend/src/App.test.js` 覆盖公共 Skill 页面入口、Agent 创建多选传参、创建后详情关联刷新和全局 mutation loading/error 状态
- [x] T096 [P] [US5] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillOpenApiTest.java` 覆盖公共/Agent Skill multipart、association、三态、typed 409、force 与 Agent 创建 skills 语义的 OpenAPI 契约

### Implementation for User Story 5

- [x] T097 [US5] 在 `oryxos-web/src/main/frontend/src/api/skills.js` 实现公共包与 canonical Agent 关联请求、FormData boundary、normal/force delete 和统一 ApiError
- [x] T098 [US5] 在 `oryxos-web/src/main/frontend/src/components/AgentSkillsTab.vue` 展示实际关联/可关联/invalid link，只提供 associate/unlink 并移除 Agent 私有上传、启停和删除 UI
- [x] T099 [US5] 在 `oryxos-web/src/main/frontend/src/components/SkillManagementPanel.vue` 实现公共 ZIP 导入、详情、全局启停、信任提示和列出 linkedAgents 的 A→B 强删二次确认
- [x] T100 [US5] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLifecycleService.java` 与 `AgentStore.java` 实现新 Agent 暂存目录内写 AGENT.md+全部标准链接后原子发布、失败清理和无 example 脚手架
- [x] T101 [US5] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentApiController.java`、`dto/CreateAgentRequest.java`、`SaveFilesRequest.java` 与 `AgentView.java` 将 skills 定义为待建公共链接，草稿/AGENT.md 不保存名单且详情从 SkillAssociationService 派生
- [x] T102 [US5] 在 `oryxos-web/src/main/frontend/src/App.vue` 接入公共 Skill 页面、Agent 详情 Skill tab 和 Agent 创建多选，所有 mutation 期间禁用重复操作且仅 200 后刷新
- [x] T103 [US5] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillApiController.java`、`AgentSkillApiController.java` 与 `AgentApiController.java` 补齐 OpenAPI 注解、multipart schema、typed 409 和 force/创建关联说明
- [x] T104 [US5] 运行并修复 `oryxos-web/src/main/frontend/src/components/SkillManagementPanel.test.js`、`AgentSkillsTab.test.js`、`App.test.js` 与 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentApiControllerTest.java`，完成 US5 管理台闭环

**Checkpoint**: 五个 Story 均可通过 REST/管理台独立验收。

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 清理旧旁路、补齐文档治理与执行全量门禁。

- [x] T105 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillLegacyCompatibilityTest.java` 与 `oryxos-web/src/test/java/io/oryxos/web/controller/WorkspaceApiControllerTest.java` 覆盖旧 `skills/*.md`/真实目录 unmanaged、generic files API 不覆盖/跟随关联和一个 invalid Skill 不影响其它 Agent
- [x] T106 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLifecycleService.java` 与 `oryxos-web/src/main/java/io/oryxos/web/controller/WorkspaceApiController.java` 让 Agent 删除和 files mutation 复用 graph→Agent 写锁，并拒绝通用 API 改写 `skills/` 关联入口
- [x] T107 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillLoader.java`、`SkillRegistry.java`、`SkillService.java`、`SkillManagementService.java` 与 `oryxos-web/src/main/java/io/oryxos/web/controller/` 删除不再使用的私有 CRUD、eager body 和重复关联实现，只保留明确 deprecated 且委托单一服务的入口
- [x] T108 [P] 在 `docs/TechnicalSolution.md`、`docs/AiProgrammingGuide.md`、`README.md`、`AGENTS.md` 与 `CLAUDE.md` 同步公共根、标准链接、parser、L1/L2/L3、全局状态、简单强删边界、信任和 legacy 行为
- [x] T109 [P] 在 `specs/012-skill-management/governance-amendment.md` 更新最终实现证据，醒目标注宪章 v2.0.0 Principle IV/VIII、无 YAML/数据库关联、无 Tool 扩权和无第二公共根
- [x] T110 在 `specs/012-skill-management/quickstart.md` 逐项执行并记录 parser 矩阵、工作区移动、REST、渐进加载、禁用重启、A→B 强删、同进程故障和管理台结果
- [x] T111 在 `pom.xml` 对应 Maven 门禁下运行 `mvn clean verify` 与指定 Skill Boot E2E，修复 Spotless/P3C/Checkstyle/SpotBugs/安全扫描和测试失败
- [x] T112 在 `oryxos-web/src/main/frontend/package.json` 对应门禁下运行 `npm test -- --run` 与 `npm run build`，修复前端单测、可访问性和生产构建失败
- [x] T113 在 `specs/012-skill-management/governance-amendment.md` 汇总最终测试命令、兼容/安全影响与 PR `Governance Amendment / 治理修订` 区块，确认不存在 journal/recovery 任务或实现

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 无依赖，可立即开始。
- **Phase 2 Foundational**: 依赖 Phase 1，阻塞所有 Story。
- **US1 (Phase 3)**: 依赖 Foundational；这是运行时 MVP。
- **US2 (Phase 4)**: 依赖 US1 的 parser/catalog/association，以完成“导入后关联并发现”的独立验收。
- **US3 (Phase 5)**: 依赖 US1 snapshot/graph lock 与 US2 management/API 骨架。
- **US4 (Phase 6)**: 依赖 US1 association/graph lock 与 US2 management/archive 骨架；可与 US3 在不同文件稳定后部分并行。
- **US5 (Phase 7)**: 依赖 US2–US4 的 REST 契约和 US1 association；Agent 创建后端可在前端组件开发期间并行。
- **Polish (Phase 8)**: 依赖计划交付的全部 Story。

### User Story Dependency Graph

```text
Setup → Foundational → US1 → US2 ─┬→ US3 ─┐
                                  └→ US4 ─┼→ US5 → Polish
                                          ┘
```

### Within Each User Story

- 先提交测试任务并确认因缺少目标行为而失败，再实现对应模型/服务/接口。
- parser/值对象先于 catalog；catalog/association 先于 snapshot；snapshot/graph lease 先于 runtime/Tool guard E2E。
- importer 先于 management publish；service 先于 REST；REST 先于前端。
- force delete 必须先完成冲突扫描、锁序和补偿测试，禁止引入 journal/recovery 类型“顺手增强”。
- Story checkpoint 通过后再进入依赖它的下一 Story。

### Parallel Opportunities

- T001–T003 可在 Workspace、配置和测试夹具上并行。
- Foundational 中 T005/T007/T009/T011 可并行先写不同测试文件。
- US1 parser 测试 T015–T019、catalog/link 测试 T020–T024、runtime 测试 T025–T032 可按不同文件分组并行。
- US2 安全导入测试 T050–T057 可并行；T061 DTO 可与 core importer 实现 T059/T060 并行。
- US3 的 core/API 测试 T068–T071 可并行。
- US4 的扫描/归档/并发/补偿/API 测试 T077–T082 可并行。
- US5 的前端三个测试、Agent lifecycle、Controller 与 OpenAPI 测试 T090–T096 可并行；T097–T099 与 T100–T101 可前后端并行。
- Polish 的文档 T108、治理 T109 与 legacy 测试 T105 可并行。

---

## Parallel Examples

### User Story 1

```text
并行组 A（parser）: T015 T016 T017 T018 T019
并行组 B（catalog/link）: T020 T021 T022 T023 T024
并行组 C（runtime）: T025 T026 T027 T028 T029 T030 T031 T032
实现主链: T034 → T035 → T036 → T037 → T038 → T039/T040 → T041/T042 → T043 → T044/T045 → T046/T048 → T033/T049
```

### User Story 2

```text
并行安全测试: T050 T051 T052 T053 T054 T055 T056 T057
实现主链: T059 → T060；T061 可并行准备 DTO，随后 T062 → T063/T064/T065/T066 → T058/T067
```

### User Story 3

```text
并行测试: T068 T069 T070 T071
实现主链: T072 → T073/T074/T075 → T076
```

### User Story 4

```text
并行测试: T077 T078 T079 T080 T081 T082
实现主链: T083/T084 → T085 → T087 → T086 → T088/T089
```

### User Story 5

```text
并行测试: T090 T091 T092 T093 T094 T095 T096
前端链: T097 → T098/T099 → T102/T104
后端链: T100 → T101 → T103/T104
```

---

## Implementation Strategy

### MVP First（US1）

1. 完成 Setup 与 Foundational。
2. 完成 US1 parser、公共 catalog、标准软链接、snapshot、lease 与 L2/L3 guard。
3. 运行双 Skill 唯一标记 E2E；确认首轮正文为 0、未命中读取为 0、审计完整。
4. 在该 checkpoint 停止并演示，不需要先完成导入/UI。

### Incremental Delivery

1. **US1**: 手工公共包 + 标准链接即可渐进加载。
2. **US2**: 加入安全 ZIP 导入和 REST，形成可调用市场核心。
3. **US3**: 加入全局启停与跨重启状态。
4. **US4**: 加入普通/强制删除和归档留痕。
5. **US5**: 接管理台与 Agent 创建事务，形成产品闭环。

### Scope Guards

- 不实现反向关联索引、缓存或数据库关联表；删除每次扫描全部 Agent。
- 不实现持久化 force-delete journal、启动恢复或跨进程原子保证。
- 不新增远程 Marketplace 协议、签名、版本依赖求解或 `use_skill` Tool。
- 不通过 `AGENT.md`/`AGENTS.md` 保存关联，不把 Skill 放入 ToolRegistry，不扩大 Tool 权限。

---

## Notes

- 所有 `[P]` 任务必须确认不修改同一文件后才能真正并行。
- Story 测试任务先执行并观察失败，随后再做实现任务。
- 每个逻辑组完成后提交，避免把 parser、runtime、CRUD 和 UI 混成一个不可评审提交。
- PR 必须包含醒目的 `Governance Amendment / 治理修订` 区块。
