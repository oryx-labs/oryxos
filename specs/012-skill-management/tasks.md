# Tasks: Agent 内 Skill 渐进式加载与生命周期管理

**Input**: Design documents from `/specs/012-skill-management/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`

**Tests**: 本特性的规格明确要求 parser、安全、并发、REST、重启和前端自动化覆盖；各 User Story 均采用测试先行，先让对应测试失败，再实现到通过。

**Organization**: 任务按 User Story 分组。US1 是运行时核心，刻意细拆 frontmatter 状态机、稳定错误模型、坏项隔离、catalog、请求快照、L1/L2/L3 和读写租约；US2–US4 在同一管理服务上完成导入与简单 CRUD；US5 完成管理台闭环。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：仅表示可与同阶段其他标记任务并行，涉及不同文件且无未完成依赖。
- **[Story]**：映射到 `spec.md` 的 US1–US5；Setup/Foundation/Polish 不加 Story 标签。
- 每项任务均包含精确仓库路径；同一文件上的后续任务按编号顺序执行。

## US1 解析契约落地说明

用户给出的 Rust 解析流程按以下 Java 21 / OryxOS 契约落地，后续任务和测试以此为准：

1. 输入先按 `content.replace("\r\n", "\n").replace('\r', '\n')` 的等价语义归一化；仅剥文件最开头一个 UTF-8 BOM，仅跳过前导换行，不跳过空格或 Tab。
2. opening fence 必须是独立首行（允许行尾空白，但拒绝 `---yaml`）；closing fence 逐行扫描，整行 `strip()` 后必须等于 `---`。Java 使用字符/行位置，不做 UTF-8 字节切片；原始字节计数只用于安全上限。
3. 严格 Skill 解析区分 `MISSING_FRONTMATTER`、`UNCLOSED_FRONTMATTER`、`INVALID_UTF8`、`INVALID_YAML`、`EMPTY_PROMPT` 等稳定错误；旧 `AGENT.md` 继续保留无 frontmatter 时整篇正文的宽松兼容行为。
4. YAML 使用项目现有 SnakeYAML `SafeConstructor + LoaderOptions`，而非 Rust 的 `serde_norway`；禁止 custom tag、duplicate key、alias，并限制 code points 与 nesting depth。
5. Skill 名称遵循已确认的开放 Agent Skills 契约 `^[a-z0-9]+(?:-[a-z0-9]+)*$`、1–64 字符且等于父目录，不采用更宽松的大小写/点/下划线语法。
6. OryxOS v1 没有顶层 `version` 或 XML `<skill version>` 输出；`metadata.version` 只是 String 展示元数据。`activation`/`requires` 不建模、不执行，只受通用 YAML 大小/深度限制；`metadata.openclaw.requires` 仅脱敏 WARN 后忽略，任何这些字段都不能启用 Skill、扩权或触发 Tool。
7. parser 返回 frontmatter、正文起点和 `hasNonBlankPrompt`；catalog/snapshot 只保存 `SkillMetadata`，不保存正文。正文非空检查允许有界扫描到首个非空白字符，但 L2 正文仍只能在命中后经既有 `read_file` 读取。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 引入安全 ZIP 检查所需的唯一生产依赖，并保持 Maven 统一版本管理。

- [X] T001 在 `pom.xml` 增加 Apache Commons Compress `1.28.0` 版本与 dependencyManagement，并在 `oryxos-core/pom.xml` 增加最小生产依赖，确认未新增 Skill Maven 模块或 Tool 运行时依赖

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 统一 Agent 身份、限制配置和不可变领域对象；这些是所有 User Story 的共同前置。

**⚠️ CRITICAL**: 本阶段完成前不得开始任何 User Story 实现。

- [X] T002 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentNameTest.java` 编写安全字符、空值、目录 basename/Profile 精确一致及 ASCII lower-case lock key 的失败优先测试
- [X] T003 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentName.java` 实现统一解析，并改造 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentStore.java` 与 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLoader.java` 删除重复名称正则、强制目录名与 Profile.name 精确一致
- [X] T004 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillLimitsTest.java` 与 `oryxos-cli/src/test/java/io/oryxos/cli/config/SkillPropertiesTest.java` 编写默认值、全部正数、大小关系、Duration 和非法配置点名失败测试
- [X] T005 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillLimits.java`、`oryxos-cli/src/main/java/io/oryxos/cli/config/SkillProperties.java`、`oryxos-cli/pom.xml` 与 `oryxos-boot/src/main/resources/application.yml` 实现 `oryxos.skills.package-limits.*`、catalog、staging TTL、multipart 安全默认值、启动校验及 CLI 配置测试依赖
- [X] T006 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillValueObjectsTest.java` 编写 defensive copy、不可变 collection、稳定 validation code/message、状态优先级、相对 entry/resource 和 snapshot 不携带正文的失败优先测试
- [X] T007 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillMetadata.java`、`SkillDescriptor.java`、`SkillSnapshot.java`、`SkillStatus.java`、`SkillSource.java`、`SkillOrigin.java`、`SkillValidationCode.java` 与 `SkillValidationError.java` 实现不可变领域对象、稳定校验表示、enabled/disabled/invalid 三态和 upload/workspace 来源模型

**Checkpoint**: Agent 身份、限制和 Skill 基础模型稳定；可以开始 US1 parser/catalog/runtime 工作。

---

## Phase 3: User Story 1 - 用到时才加载 Skill (Priority: P1) 🎯 MVP

**Goal**: 每个顶层请求只冻结并注入已启用 Skill 的 L1 元数据；命中后才通过现有 `read_file`/`shell` 取得 L2/L3，坏 Skill 被隔离且一轮 ReAct 内快照和文件保持一致。

**Independent Test**: 为同一 Agent 放置两个带唯一正文/资源标记的 Skill；首个 prompt 只含两份 name/description/entry，随后只读取命中 Skill 的 L2/L3，未命中读取次数为 0；并发禁用/删除等待当前请求完成，下一请求才看到变化。

### Tests for User Story 1

> 先完成 T008–T022 并确认失败，再进入实现任务。

- [X] T008 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/MarkdownFrontmatterTest.java` 编写表驱动状态机测试，覆盖 CRLF/单 CR、文件首 BOM、仅前导换行、opening 独立首行及尾随空白、拒绝 `---yaml`、closing 行 `strip()`、opening 后无换行、缺 opening/closing、连续 fence、中文/emoji 边界与空正文稳定错误码
- [X] T009 [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/MarkdownFrontmatterTest.java` 增加严格 UTF-8、原始字节/frontmatter/code-point/SKILL.md 上限、UTF-16 surrogate 不截断和“遇正文首个非空白字符即停止且不保存全文”的有界读取测试
- [X] T010 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentMarkdownTest.java` 补齐共享行尾/BOM/fence 回归，确保旧 Agent 继续使用宽松 YAML，并在无合法 frontmatter 时把整篇作为正文而非收到 Skill 强校验异常
- [X] T011 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillMetadataReaderTest.java` 编写标准 manifest 测试，覆盖 name/description/父目录匹配、license、compatibility、String→String metadata、allowed-tools、未知字段、正文起点和 `EMPTY_PROMPT`
- [X] T012 [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillMetadataReaderTest.java` 增加 custom tag、duplicate key、anchor/alias、嵌套/code-point 上限、非 Map 根、非 String key/value、官方小写连字符 name 边界、`metadata.openclaw.requires` 脱敏 WARN，以及 version/activation/requires/allowed-tools 绝不改变权限或执行路径的安全测试
- [X] T013 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillContentValidatorTest.java` 编写手工 workspace 包树测试，覆盖入口/resource symlink、根外 real path、特殊文件、保留 marker 损坏、文件数/深度/大小与 magic-prefix 限制，断言 `NOFOLLOW_LINKS`、相对错误消息和资源正文不被全文读取
- [X] T014 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillCatalogTest.java` 编写候选发现与隔离测试，覆盖合法、坏 YAML、空正文、含 marker 却缺入口、根 symlink、内部链接、`skills/*.md` 和无入口/marker 目录，断言单个坏项为 INVALID 且不阻断 Agent/其他 Skill
- [X] T015 [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillCatalogTest.java` 增加 enabled-only snapshot、名称排序、64 项/12,000 字符完整项预算、omittedCount/catalogIncluded、确定性 `CATALOG_TRUNCATED` WARN 和 Agent 目录整体不可读才失败的测试
- [X] T016 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderTest.java` 编写 L1 精确渲染测试，断言只含 name/description/entry，描述控制字符不能逃逸条目，正文/resource/origin/marker/metadata/allowed-tools 不出现；缺 `read_file` 只提示并 WARN、不自动扩权
- [X] T017 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillCoordinatorTest.java` 使用 `CountDownLatch`/barrier 编写无 sleep 并发测试，验证 fair reader A→writer B→late reader C 顺序、snapshot 失败解锁、lease 幂等 close、请求异常解锁和写者不饥饿
- [X] T018 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentServiceTest.java` 编写一次 process 仅建一个 immutable snapshot、租约覆盖 Profile 获取/ReAct/Session save，且 Provider/Tool/ReAct/save 异常都释放 lease 并清理 ProfileContext 的测试
- [X] T019 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/ReActLoopTest.java` 与 `oryxos-core/src/test/java/io/oryxos/core/agent/PromptBuilderTest.java` 断言所有循环轮次显式接收同一个 SkillSnapshot，不在 PromptBuilder/ContextLoader 隐式重扫、不写入 Session/Profile/ThreadLocal
- [X] T020 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentLifecycleServiceTest.java` 与 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentStoreTest.java` 编写 update/saveFiles/writeAll/delete 共用 Agent 写租约、同目录临时文件原子替换、失败保留旧文件及新脚手架生成标准 `skills/example/SKILL.md` 的回归测试
- [X] T021 [P] [US1] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/WorkspaceApiControllerTest.java` 编写受管 Skill 子树写入等待同一写租约、原子替换、非法/链接路径拒绝且读取不泄露锁内中间态的旁路测试
- [X] T022 [P] [US1] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillProgressiveDisclosureE2ETest.java` 编写两 Skill 唯一标记 E2E，断言首 prompt 只有 L1、只命中一个 L2/L3、未命中读取为 0、既有 tool_invocations 仍落库且无 `use_skill`/自动 Tool 执行

### Implementation for User Story 1

- [X] T023 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillValidationException.java` 实现携带 SkillValidationError 的严格解析异常，并补全 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillValidationCode.java` 的 MISSING/UNCLOSED frontmatter、INVALID_UTF8/YAML、UNSAFE_YAML、各级上限、INVALID_NAME、NAME_DIRECTORY_MISMATCH、description、metadata、EMPTY_PROMPT、link/special/outside-root 稳定代码；消息不得透传绝对路径或原始异常
- [X] T024 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/context/MarkdownFrontmatter.java` 实现 Java 字符/行状态机：严格 UTF-8、CRLF/CR 归一化、首 BOM、仅前导换行、opening 独立行、closing 逐行 `strip()`，使用字符位置而非 UTF-8 字节切片并返回 YAML、正文起点与 hasNonBlankPrompt
- [X] T025 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/context/MarkdownFrontmatter.java` 实现有界流式读取和错误处理：原始字节/frontmatter/code-point 上限、正文只探测到首个非空白字符、missing opening/closing/empty prompt 分码、所有异常关闭流且错误只含安全文件名
- [X] T026 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentMarkdown.java` 委托共享的行尾/BOM/fence 分割逻辑，同时保留 Agent 现有 Yaml 兼容解析、body 处理和无 frontmatter 降级，不把不可信 Skill 的 SafeConstructor 策略施加到 AGENT.md
- [X] T027 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillMetadataReader.java` 用 SnakeYAML `SafeConstructor + LoaderOptions` 禁止 custom tags、duplicate keys、aliases并限制 nesting/code points，逐字段校验官方 name/description/可选字段、父目录精确匹配与非空正文，构造结果不得携带正文
- [X] T028 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillMetadataReader.java` 增加安全 raw-tree 兼容预处理：仅检测并忽略 `metadata.openclaw.requires`、输出不含 YAML 值的稳定 WARN；metadata.version 只作 String 展示，version/activation/requires/allowed-tools 均不得注册 Tool、改变 Profile 或触发执行
- [X] T029 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillContentValidator.java` 实现 catalog/import 共用的有界包树校验，使用 `NOFOLLOW_LINKS`、real-path containment、普通文件/目录类型、保留文件规则、资源统计和最多 512-byte magic prefix，把单包问题转换成稳定 SkillValidationError
- [X] T030 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillCatalog.java` 实现每次调用重扫 `skills/` 直接子目录、managed/legacy 分类、origin/disabled marker 校验、资源统计与三态派生；根 symlink 忽略并 WARN，候选异常隔离，只有 Agent 级扫描失败才终止
- [X] T031 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillCatalog.java` 与 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillSnapshot.java` 实现 enabled-only snapshot、名称稳定排序、完整条目字符预算、人工超限确定性截断/告警和 immutable L1 集合
- [X] T032 [P] [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillLockRegistry.java` 与 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillLease.java` 实现按 AgentName.lockKey 永不移除的 fair `ReentrantReadWriteLock(true)`、checked operation 安全解锁和幂等 AutoCloseable lease
- [X] T033 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillCoordinator.java` 实现 openRequest/mutate：读锁内重检 Agent 目录与 Profile 身份并只构建一次 snapshot，扫描失败立即解锁，写锁排队后不让后续读请求持续插队
- [X] T034 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java` 增加显式 SkillSnapshot 参数和固定格式 L1 渲染，只输出 name/description/entry并消毒控制字符；缺 read_file 只提示/告警，不读取正文/resource或改变工具权限
- [X] T035 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/ReActLoop.java` 与 `oryxos-core/src/main/java/io/oryxos/core/agent/PromptBuilder.java` 增加显式 SkillSnapshot 参数并逐轮透传同一实例，生产路径不得通过 ThreadLocal、Session/Profile 或重新扫描取得 Skill
- [X] T036 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java` 用 try-with-resources 打开请求 lease，使其覆盖 Profile 解析、完整 ReAct 与正常路径 Session 保存，并保持所有异常路径 ProfileContext 清理；兼容重载只能委托 empty snapshot且不得用于生产装配
- [X] T037 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentStore.java` 与 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLifecycleService.java` 将 create/update/saveFiles/delete/writeAll 接入同一 Agent 写锁、使用同目录临时文件 + `ATOMIC_MOVE` 原子提交，并把新 Agent 示例改为合法 `skills/example/SKILL.md`
- [X] T038 [US1] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/WorkspaceApiController.java` 将 `agents/<agent>/skills/<skill>/...` 写入路由到同一 Agent 写锁并原子替换，拒绝 symlink/越界/非直接所属 Agent 路径，消除 generic files API 旁路
- [X] T039 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 注册 SkillProperties，显式装配 metadata reader、content validator、catalog、fair lock registry、coordinator并注入 AgentService/Lifecycle/Workspace；不得向 ToolRegistry 注册 Skill 或新增 Spring AI 自动执行通道
- [X] T040 [US1] 运行 `mvn -pl oryxos-core,oryxos-web,oryxos-boot -am test -Dtest=MarkdownFrontmatterTest,SkillMetadataReaderTest,SkillContentValidatorTest,AgentSkillCatalogTest,AgentSkillCoordinatorTest,SkillProgressiveDisclosureE2ETest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=`，修复 US1 引入的失败并把可复现命令同步到 `specs/012-skill-management/quickstart.md`

**Checkpoint**: US1 可独立交付为运行时 MVP；现有 workspace Skill 可渐进发现，坏项隔离，并发管理边界已建立。

---

## Phase 4: User Story 2 - 安全导入一个 Skill (Priority: P1)

**Goal**: 管理员上传单 Skill ZIP，经同盘 staging、完整安全校验和原子发布后默认启用；任何失败都不改变活动目录。

**Independent Test**: 合法 shape A/B 包导入后立即可查询且下一请求发现；重名、缺入口、Zip Slip、链接和超限包按稳定错误拒绝，活动目录和 staging 均无残留。

### Tests for User Story 2

- [X] T041 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterTest.java` 编写合法 shape A/B、wrapper/name 一致、Windows/FAT mode=0、默认 enabled、originalFilename 清洗和 `.oryxos-origin.yml` 测试
- [X] T042 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterPathSecurityTest.java` 编写 `../`、绝对路径、drive/UNC、反斜杠、NUL、空/点段、路径深度/长度、重复 entry、NFC/大小写冲突及多顶层结构攻击测试
- [X] T043 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterEntrySecurityTest.java` 编写 symlink/device/FIFO/socket、加密/不支持压缩、保留 marker、嵌套 archive、class/native 扩展名与 magic 拒绝测试
- [X] T044 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillPackageImporterLimitsTest.java` 编写压缩、实际展开总量、实际单文件、SKILL.md/frontmatter、entry 数和展开比超限测试，断言不信任 ZIP header size且稳定映射 413
- [X] T045 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementServiceTest.java` 编写 Agent 消失/归档、任意状态或 unmanaged 同名、L1 预算、不同 FileStore、原子移动失败、写锁二次校验、零活动/staging 残留及每次 mutation 恰一条脱敏日志测试
- [X] T046 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillApiControllerTest.java` 编写 GET collection/member 与 multipart POST 契约，覆盖缺/空 file、400/404/409/413/500、安全 member 单段、相对路径和响应不含绝对路径/堆栈/正文
- [X] T047 [P] [US2] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillManagementE2ETest.java` 编写上传合法包→立即列表/详情→下一请求发现，以及非法/重名包失败后活动目录不变的 E2E

### Implementation for User Story 2

- [X] T048 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/PreparedSkill.java`、`SkillImportException.java`、`SkillConflictException.java` 与 `SkillPackageTooLargeException.java` 定义暂存结果、稳定 reason code 和不携带绝对路径/原始异常文本的领域错误
- [X] T049 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillPackageImporter.java` 实现上传流按权威上限落同盘 `.oryxos/.staging/skill-import/<uuid>/upload.zip`，并用 Commons Compress central directory 检查加密、压缩方法和 Unix entry 类型
- [X] T050 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillPackageImporter.java` 实现 POSIX 路径规范化、NFC/大小写唯一键、深度/长度、containment、`CREATE_NEW` 普通文件物化及 link/special/reserved entry 拒绝
- [X] T051 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillPackageImporter.java` 实现流式实际字节/entry/单文件/总量/展开比硬限制和 512-byte magic 检查，禁止嵌套压缩、class 和 native executable
- [X] T052 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillPackageImporter.java` 实现 shape A/B 归一化、解压后 NOFOLLOW 全树复检、wrapper/name 校验、复用 SkillMetadataReader/SkillContentValidator及固定字段安全序列化 origin
- [X] T053 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillPackageImporter.java` 实现所有成功/失败/异常分支的幂等 staging 清理和 24h 孤儿清理，清理不得跟随链接或越出 staging 根
- [X] T054 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现短读锁 list/get 与 import：写锁外 prepare，写锁内重检 Agent/父链/冲突/预算/FileStore，仅 `ATOMIC_MOVE` 发布且不降级，并为一次 service mutation 只写一条安全结构化事件
- [X] T055 [P] [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/SkillSummaryView.java`、`SkillDetailView.java` 与 `SetSkillEnabledRequest.java` 实现三态、configuredEnabled、catalogIncluded、来源、校验错误、相对 entry/resources 和 epoch-millis 信封映射
- [X] T056 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentSkillApiController.java` 实现 GET collection/member 和 multipart POST，使用 `@RequestPart(required=false)` 与 `MultipartFile.getInputStream()`，安全解析单段 member且不把 Web 类型传入 core
- [X] T057 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/GlobalExceptionHandler.java` 映射 Skill 领域异常到 400/404/409/413，并将 `MaxUploadSizeExceededException` 映射 413，未预期 I/O 只返回通用 500
- [X] T058 [US2] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 装配 importer/management service并启动 staging 清理；实现后跑通 `oryxos-boot/src/test/java/io/oryxos/boot/SkillManagementE2ETest.java`

**Checkpoint**: US1 + US2 构成首个可管理的 P1 闭环；无需手改目录即可安全导入并在下一请求渐进使用。

---

## Phase 5: User Story 3 - 禁用与重新启用 Skill (Priority: P2)

**Goal**: 用包内零字节 marker 持久化管理员启用设置；禁用从下一请求排除，启用前完整重校验，旧会话历史不被改写。

**Independent Test**: 禁用合法 workspace Skill 后重启仍不可发现；重新启用后下一新请求恢复；运行中请求持租约时 toggle 等待，旧 Session 内容保持不变。

### Tests for User Story 3

- [X] T059 [P] [US3] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementServiceTest.java` 编写 enabled/disabled/invalid 状态机，覆盖零字节 marker、幂等、invalid 可禁用、启用前重校验、预算不足保持 marker、读租约期间等待和每次调用恰一条日志
- [X] T060 [P] [US3] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillApiControllerTest.java` 编写 PUT boolean 校验、幂等切换、invalid 启用 400、不存在 404、失败不泄露内部路径测试
- [X] T061 [P] [US3] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillRestartRecoveryIT.java` 编写 marker 跨两个独立 Spring Context、禁用后新 Session 不发现、启用后恢复且旧 Session 历史/Tool/LLM 审计未被改写的集成测试

### Implementation for User Story 3

- [X] T062 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现写锁内 disable/enable：原子创建零字节 `.oryxos-disabled`，enable 前完整重校验包和聚合预算，成功才删除 marker，所有幂等/失败分支返回最新 descriptor
- [X] T063 [US3] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentSkillApiController.java` 实现 PUT member并用 `SetSkillEnabledRequest` 严格接收 JSON boolean，返回最新 SkillDetailView且从下一顶层请求生效

**Checkpoint**: US3 可用手工 workspace Skill 独立验证，不依赖导入成功路径；禁用状态跨重启且不追溯篡改历史。

---

## Phase 6: User Story 4 - 删除并留痕 (Priority: P2)

**Goal**: 删除任意状态受管 Skill 时，从活动区原子移入安全归档事件并保存来源/Agent/Skill/时间；失败保持活动包不变。

**Independent Test**: 删除一个手工 workspace Skill 后下一请求不可发现，活动目录消失，归档含完整 package/archive.yml；删除不存在项 404 且无副作用。

### Tests for User Story 4

- [X] T064 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementServiceTest.java` 编写 enabled/disabled/invalid 删除、404、归档 metadata、来源保留、读租约结束前不移动及一次 mutation 恰一条日志测试
- [X] T065 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillArchiveSecurityTest.java` 编写 archive 父链 symlink、越界 realpath、不同 FileStore、原子移动不支持、metadata 写入/移动失败测试，断言活动包原位且无完成态假归档
- [X] T066 [P] [US4] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillApiControllerTest.java` 编写 DELETE 三态、404、非法 member 单段及成功 `data=null`/epoch-millis 信封测试

### Implementation for User Story 4

- [X] T067 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/ArchivedSkill.java` 实现固定字段 archive.yml 安全序列化，事件名使用 UTC 基本时间 + UUID，Skill 名只进入 metadata而不参与归档路径
- [X] T068 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillManagementService.java` 实现写锁内 delete：验证 archive 全父链 NOFOLLOW/realpath/FileStore，先写事件 metadata，再仅用 `ATOMIC_MOVE` 移至 package，失败清理未完成事件且不降级复制
- [X] T069 [US4] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentSkillApiController.java` 实现 DELETE member；不存在、symlink 或 unmanaged 目标返回 404，成功返回 ApiResponse<Void>
- [X] T070 [US4] 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillManagementE2ETest.java` 增加删除后下一请求/L1/L2 无记录、活动目录为零、归档完整、不存在项无副作用和超时未完成归档清理断言

**Checkpoint**: US4 完成简单 CRUD 管理闭环；活动状态和可追溯归档不存在中间可见态。

---

## Phase 7: User Story 5 - 在管理台完成 Skill 管理 (Priority: P3)

**Goal**: Agent 详情增加 Skill 页签，完成列表/详情、上传、禁用/启用和确认删除，并正确显示三态、校验错误和信任边界。

**Independent Test**: 组件测试用假 API 完成导入→查看→禁用→启用→删除；任意失败不乐观修改原行，实际 `/admin/` 可完成同样闭环。

### Tests for User Story 5

- [X] T071 [US5] 在 `oryxos-web/src/main/frontend/package.json`、`oryxos-web/src/main/frontend/package-lock.json`、`oryxos-web/src/main/frontend/vite.config.js` 与 `oryxos-web/pom.xml` 加入 Vitest、Vue Test Utils、jsdom、test script和 Maven 前端测试 gate
- [X] T072 [P] [US5] 在 `oryxos-web/src/main/frontend/src/api/skills.test.js` 编写五个 REST 操作、统一信封失败、member encodeURIComponent、FormData `file` 且不手工设置 multipart Content-Type 的测试
- [X] T073 [P] [US5] 在 `oryxos-web/src/main/frontend/src/components/AgentSkillsTab.test.js` 编写三态/invalid、信任提示、loading/empty/error、collection/行级 busy、防重复、失败保留、成功刷新和删除二次确认测试

### Implementation for User Story 5

- [X] T074 [US5] 在 `oryxos-web/src/main/frontend/src/api/skills.js` 实现 list/detail/import/setEnabled/delete 薄客户端、统一信封检查、安全 member 编码和 FormData 上传
- [X] T075 [US5] 在 `oryxos-web/src/main/frontend/src/components/AgentSkillsTab.vue` 实现名称、描述、enabled/disabled/invalid、来源、更新时间、catalogIncluded、validationError 与详情/资源统计展示，并覆盖 loading/empty/error 三态
- [X] T076 [US5] 在 `oryxos-web/src/main/frontend/src/components/AgentSkillsTab.vue` 实现 ZIP 上传和“Skill 等同代码，仅导入已审查来源”提示、上传/逐行 busy、启停、删除确认、服务端成功后刷新及失败保留原状态
- [X] T077 [US5] 在 `oryxos-web/src/main/frontend/src/App.vue` 为 Agent 详情接入 Skill 页签，传递服务端 Agent name，切换 Agent 时重置旧状态且不引入 Router
- [X] T078 [US5] 运行 `npm test -- --run` 与 `npm run build` 验证 `oryxos-web/src/main/frontend/package.json` 的 API/组件测试和生产构建，并确认 `oryxos-web/src/main/resources/static/admin/` 产物由构建生成而非手工编辑

**Checkpoint**: 管理台完成可测试的 Skill 生命周期闭环，危险操作均有信任提示、确认和失败反馈。

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 统一日志/OpenAPI/文档，完成安全回归和全部质量门禁。

- [X] T079 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillManagementLoggingTest.java` 与 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillApiControllerTest.java` 验证每个进入 service 的 mutation 恰一条 `skill.management`、transport 提前拒绝为零条，日志无正文、绝对路径、密钥或未清洗异常
- [X] T080 在 `oryxos-boot/src/main/resources/logback-spring.xml` 为开发 pattern 加 `%kvp`，保持 prod JSON encoder 输出 fluent key-value且不重复记录管理事件
- [X] T081 [P] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillOpenApiTest.java` 编写 multipart file、三态 DTO 与 400/404/409/413 schema 测试，并在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentSkillApiController.java` 补齐 springdoc 注解
- [X] T082 [P] 在 `AGENTS.md`、`CLAUDE.md`、`docs/TechnicalSolution.md`、`docs/AiProgrammingGuide.md` 与 `README.md` 同步 Agent 私有 Skill 目录、L1/L2/L3、信任边界、disabled/delete、legacy、无 use_skill/不扩权和标准脚手架示例
- [X] T083 在 `specs/012-skill-management/quickstart.md` 按最终实现复核 REST/UI 导入→禁用→重启→启用→删除、并发等待、恶意包、legacy、日志和本地启动命令
- [X] T084 在 `oryxos-boot/src/test/java/io/oryxos/boot/SkillManagementE2ETest.java` 完成真实 REST 管理闭环回归，断言所有管理动作从下一请求生效、旧历史不被修改且 tool_invocations 仍只来自既有 read_file/shell
- [X] T085 运行 `mvn clean verify` 验证根 `pom.xml` 及 core/web/boot 单元、集成、格式、P3C、Checkstyle、SpotBugs/Find Security Bugs门禁，并修复仅由本特性引入的失败
- [X] T086 运行 `mvn -pl oryxos-boot -am test -Dtest=SkillRestartRecoveryIT,SkillManagementE2ETest,SkillProgressiveDisclosureE2ETest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=` 验证 `oryxos-boot/src/test/java/io/oryxos/boot/` 的重启、并发和渐进披露验收
- [X] T087 运行 `npm test -- --run`、`npm run build` 和 `mvn -pl oryxos-web -am verify` 验证 `oryxos-web/src/main/frontend/package.json` 与 `oryxos-web/pom.xml` 的前端独立/Maven 双重门禁，并按 `specs/012-skill-management/quickstart.md` 完成最终人工闭环

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始。
- **Foundational (Phase 2)**: 依赖 T001；完成后才可开始任何 User Story 实现。
- **US1 (Phase 3)**: 依赖 Foundation，是运行时和后续管理故事的核心基础。
- **US2 (Phase 4)**: 依赖 US1 的 parser、content validator、catalog、limits 和 coordinator；四组 ZIP 安全测试可在 US1 API 稳定后并行编写。
- **US3 (Phase 5)**: 依赖 US1 的 snapshot/lease 与 US2 的 management service/Controller 骨架；独立验收可直接使用 workspace Skill，不要求先成功导入。
- **US4 (Phase 6)**: 依赖 management service、member 安全解析和写租约；可删除手工 workspace Skill，不依赖 US3 的 enable/disable 实现。
- **US5 (Phase 7)**: 依赖 US2–US4 REST 契约稳定。
- **Polish (Phase 8)**: 依赖计划交付的全部 User Story。

### User Story Dependency Graph

```text
Setup → Foundation → US1 → US2 ─┬→ US3 ─┐
                                └→ US4 ─┴→ US5 → Polish
```

US4 实现复用 US2 建立的 management service/REST skeleton，但业务语义不依赖 US3；若多人并行，先合并共享骨架再分别实现 toggle 和 archive delete。

### Within Each User Story

- 先提交该 Story 的测试并确认以预期原因失败，再写领域对象/服务/适配器。
- US1 内部顺序：错误契约与 parser → safe YAML/内容校验 → catalog/snapshot → lock/lease → ContextLoader/ReAct/AgentService → 写入旁路 → E2E。
- US2 内部顺序：ZIP fixtures → staging/central directory → path/stream limits → package normalization → management atomic publish → REST/E2E。
- US3/US4：领域状态测试 → service 写锁切换 → REST → restart/E2E。
- UI 测试先于 API client/组件实现；服务端成功前不得乐观改变状态。

### Parallel Opportunities

- Foundation 中 AgentName、SkillLimits 和 value-object 测试可并行。
- US1 的 parser、metadata、content tree、catalog、ContextLoader、lock、runtime wiring测试位于不同文件，可并行准备；同一实现文件上的任务按编号串行。
- US2 的 path、entry-type、limit、安全管理、REST 和 Boot 测试可并行。
- US3 的 service、REST、restart 测试可并行；US4 的 archive 安全与 REST 测试可并行。
- US5 的 API client 测试和组件测试可并行；Polish 的日志、OpenAPI 和文档可并行。

---

## Parallel Examples

### US1 parser/catalog 测试批次

```text
T008–T009 MarkdownFrontmatter 状态机与有界读取
T011–T012 SkillMetadataReader 安全 YAML/manifest
T013      SkillContentValidator 文件树安全
T014–T015 AgentSkillCatalog 隔离/预算
T016      ContextLoader L1 精确输出
T017      AgentSkillCoordinator 公平租约
```

### US2 ZIP 攻击矩阵批次

```text
T041 合法包与来源
T042 路径/Unicode 冲突
T043 entry 类型/magic
T044 资源上限
T045 原子发布/并发/日志
T046 REST 安全信封
```

---

## Implementation Strategy

### Runtime MVP First (US1)

1. 完成 Setup + Foundation。
2. 完成 US1 全部失败优先测试。
3. 按 parser → catalog → snapshot/lease → runtime wiring 实现。
4. 在 T040 停下，独立验证“两个 Skill、只加载命中一个”和并发 writer fairness。
5. 此时已有可手工维护的渐进式 Skill runtime，不需要等待 CRUD/UI。

### P1 Managed MVP

1. 在 Runtime MVP 上完成 US2。
2. 验证合法 ZIP 导入后下一请求可发现，所有恶意/超限输入零活动残留。
3. 发布第一个既可渐进运行、又可经 REST 安全导入的 P1 闭环。

### Incremental Delivery

1. **US3**：先用 marker 获得可逆止用能力。
2. **US4**：再增加可追溯归档删除。
3. **US5**：最后把稳定 REST 契约接入管理台。
4. **Polish**：合并日志、OpenAPI、文档和全量门禁。

---

## Notes

- `[P]` 仅代表文件级并行机会，不代表可以跳过同 Story 的前置 API/错误契约。
- `allowed-tools`、version、activation、requires 均不能成为权限或执行旁路；L2/L3 始终经过现有 ToolExecutor、SandboxChecker 和审计。
- disabled/deleted 不改写旧 Session 或审计；负向发现测试必须使用新 Session，并另断言旧历史完整。
- 导入格式合法不等于内容可信；UI/文档必须把上传作为代码级信任边界。
- 每完成一个任务或逻辑组即运行最小相关测试；每个 Story checkpoint 都可以独立演示和回滚。
