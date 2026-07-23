# Phase 0 Research: Agent 内 Skill 渐进式加载与生命周期管理

## 1. Skill 边界与开放格式

**Decision**

- Skill 是某个 Agent 的私有组成，受管路径只认 `.oryxos/agents/<agent>/skills/<skill>/SKILL.md`。
- 包格式对齐 [Agent Skills Specification](https://agentskills.io/specification)：`SKILL.md` frontmatter 必须有 `name`、`description`；支持 `license`、`compatibility`、`metadata`、`allowed-tools` 可选字段，以及 `scripts/`、`references/`、`assets/` 等按需资源。
- `name` 使用标准的 1–64 位小写 ASCII 字母、数字和连字符规则，且必须与父目录一致；`description` 为 1–1024 字符并描述“做什么、何时用”。
- 不建立跨 Agent 共享库、全局索引或 `use_skill` Tool。`allowed-tools` 是来源包的说明信息，不改变 Profile 中显式声明的工具权限。

**Rationale**

开放规范已经定义了三层披露：启动时加载元数据、命中时加载 `SKILL.md` 正文、需要时加载资源。它与 OryxOS 宪法 IV 的“一个目录 = 一个 Agent；子指令经 `read_file`/`shell` 按需取用”一致。目录局部扫描也避免把 Agent 私有指令错误升级成全局能力。

**Alternatives considered**

- 全局 Skill 库：会产生跨 Agent 能力索引和授权问题，违反宪法 IV。
- 新增 `use_skill`：形成新的执行通道，绕开现有 ToolExecutor、沙箱和审计。
- 自定义非标准 frontmatter：没有收益，并降低生态兼容性。

## 2. L1/L2/L3 加载链路与请求一致性

**Decision**

- `AgentService.process(...)` 是顶层请求边界：取得 Agent 读租约后只构建一次不可变 `SkillSnapshot`，再显式沿 `AgentService → ReActLoop → PromptBuilder → ContextLoader` 传递。
- `ContextLoader` 在现有 Agent identity、`AGENT.md` 正文和 Bootstrap 后追加按 Skill 名排序的 L1 目录；每项只有 `name`、`description` 和可直接交给 `read_file` 的真实 entry path。
- L2 由模型命中后调用既有 `read_file(entry)`；L3 参考资料继续用 `read_file`，脚本继续用 `shell`。不自动授予 Profile 原本没有的 `read_file`/`shell`。
- 读租约持有到整个 ReAct 和会话保存完成。导入最终发布、启用/禁用、删除、Agent 删除、`AgentLifecycleService.saveFiles`/`AgentStore.writeAll` 及 Workspace API 对受管 Skill 子树的写入使用同一 Agent 的公平写租约。
- 锁注册表使用 `ConcurrentHashMap<CanonicalAgentName, ReentrantReadWriteLock>`，锁为 fair；进程生命周期内不移除锁对象。
- 抽取统一 `AgentName`：原值继续遵守现有 `[A-Za-z0-9_-]+`，AgentLoader 强制目录 basename 与 Profile.name 精确一致；锁键使用 ASCII lower-case 形式，避免大小写不敏感文件系统为同一路径创建两把锁。AgentStore、管理 API、catalog 和 lifecycle 全部复用该解析器。

**Rationale**

`ReActLoop` 每一轮都会重新构建 Prompt；若在 `ContextLoader` 或 `PromptBuilder` 里重新扫描，中途管理操作会改变同一请求的目录。只冻结元数据但立即释放锁也不够，因为 L2 文件通常在后续轮次才读取。公平读写租约同时保证当前请求路径有效，并防止写操作排队后被不断到来的新请求饿死。

**Alternatives considered**

- 每轮现扫：破坏请求内一致性并重复 I/O。
- ThreadLocal 快照：隐藏依赖，直接测试 ReActLoop 时容易漏设；显式参数更易验证。
- 快照时复制完整 Skill：预读正文，违背渐进式披露。
- 不可变版本目录加引用计数：可避免长读锁，但第一版复杂度过高。
- 各类自行校验 Agent 名：当前 AgentStore 私有 regex 与 AgentLoader 的隐含约定可能漂移，无法形成可靠锁键。

## 3. Frontmatter 读取与 legacy 兼容

**Decision**

- 从现有 `AgentMarkdown` 抽取通用 `MarkdownFrontmatter`：流式读取到第二个 `---` 即停止，设置 64 KiB frontmatter 硬上限，不为构建 L1 读取正文。
- `MarkdownFrontmatter` 只负责 fence/有界文本提取；`AgentMarkdown` 可委托其字符串拆分但保留既有 Agent YAML 兼容行为。只有不可信 Skill frontmatter 强制 SnakeYAML `SafeConstructor + LoaderOptions`：禁止 custom tags 和 duplicate keys，aliases 上限为 0，nesting depth 上限 8，code-point 上限与 frontmatter 预算一致；metadata 只接受 String→String。
- 仅扫描 `skills/<direct-child>/SKILL.md`。现有 `skills/*.md`、`skills/SKILL.md` 都保持 legacy/unmanaged：不自动迁移、不进入 L1、不进入管理 API。
- 受管候选必须含 `SKILL.md` 或 OryxOS 保留 marker；候选缺入口、入口/元数据非法时管理面显示 `invalid`，运行时快照隔离该项。没有入口也没有保留 marker 的普通子目录按 legacy/unmanaged 忽略，避免误管旧资源目录。
- catalog、snapshot 和详情资源遍历全部使用 `NOFOLLOW_LINKS`。`skills/` 下的根 symlink 完全不跟随、不列为候选并写安全 WARN；真实包目录内的 `SKILL.md`/任一后代若是链接、特殊文件或真实路径越出 Skill 根，则候选为 invalid 且绝不进入 L1。该规则同样适用于手工 workspace Skill，而不只适用于 ZIP 导入。
- 为保持“手工改动下一请求生效”且不依赖递归 WatchService/cache，snapshot 会对受管候选做有界安全扫描：frontmatter 到第二个 fence、所有后代 `lstat`/size、每个普通文件最多 512 bytes magic prefix。它不会全文读取正文/resource，更不会把内容注入 prompt。
- 新建 Agent 的脚手架改为 `skills/example/SKILL.md`；已有平铺文件原样保留。

**Rationale**

当前 `AgentMarkdown.split(String)` 会先读完整文件，而 L1 只需要 frontmatter。现有渐进式披露测试和脚手架都使用平铺 `skills/*.md`，把它们自动包装或纳入发现会静默改变旧 Agent 行为。

**Alternatives considered**

- 继续全文读取再切 frontmatter：实现简单，但 Skill 越大越背离 L1 的成本目标。
- 启动时迁移平铺文件：不可逆且会改变 Git/人工维护目录。
- 把平铺文件也加入 L1：旧 Agent 会突然获得此前没有的自动发现行为。
- 对手工目录跟随链接：现有沙箱只做词法路径前缀判断，可能经链接读到工作区外，拒绝。

## 4. 安全 ZIP 导入

**Decision**

- Controller 以流方式把上传写入 `.oryxos/.staging/skill-import/<uuid>/upload.zip`，再解包到同一暂存事件下的 `unpacked/`；不使用系统 `/tmp`，确保最终目录与目标尽量处于同一 `FileStore`。
- 使用 Apache Commons Compress 1.28.0 读取 ZIP central directory，检测并拒绝符号链接、特殊文件、加密条目、重复条目及不支持的压缩方法；实际创建文件、路径校验和移动仍使用 JDK NIO。
- 每个 entry 拒绝绝对路径、盘符、UNC、反斜杠、NUL、`.`/`..` 段；路径先做 Unicode NFC，再做大小写规范化重复检查；`root.resolve(name).normalize()` 必须仍以 root 开头。
- 只用 `CREATE_NEW` 创建普通文件，不恢复 owner、权限或执行位。压缩大小、实际单文件/总解压字节、entry 数、深度、路径长度和解压比都做流式硬限制，不信任 ZIP 声明大小。
- entry 声明 Unix mode 时只接受 directory/regular-file，拒绝 symlink 和其他 mode；Windows/FAT 等未声明 Unix mode（mode=0）的普通 entry 仍可按 `isDirectory` 接受，并始终用 `CREATE_NEW` 物化为普通文件。按扩展名与 magic bytes 双重拒绝嵌套 archive/JAR/class/native binary（精确清单见 package contract），不尝试解释非标准“硬链接”。
- 接受两种单包布局：根目录直接是 `SKILL.md`，或 ZIP 只有一个与 metadata `name` 相同的顶层目录。导入包自带 `.oryxos-disabled` 或 `.oryxos-origin.yml` 时拒绝。
- 完整验证并写入来源元数据后才短暂取得 Agent 写租约，使用 `NOFOLLOW_LINKS` 重检 Agent/skills 父链均为真实目录、目标未占用且 FileStore 一致，然后执行 `Files.move(stagedPackage, activePackage, ATOMIC_MOVE)`。不支持原子移动时失败，不降级为复制/普通移动。
- 启动时清理超过 24 小时的孤儿 staging 事件。

**Rationale**

JDK 21 的 [`ZipInputStream`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/ZipInputStream.html) 不读取 central directory，公开 `ZipEntry` 也不暴露 Unix 文件类型，无法严格证明并拒绝 ZIP 内的 symlink 标记。Commons Compress 的 [`ZipArchiveEntry.isUnixSymlink()`](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipArchiveEntry.html) 和 external attributes 补足这一处元数据能力。JDK [`Files.move(..., ATOMIC_MOVE)`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)) 则提供发布边界；失败时不降级，才能保证活动目录永远没有半包。

**Alternatives considered**

- 纯 JDK 解压并把 symlink entry 当普通文件：不会物化链接，但无法满足“检测并拒绝”的验收语义。
- 手写 ZIP central-directory 解析：需正确处理 ZIP64 和恶意畸形包，风险高于一个聚焦依赖。
- 直接向活动目录解压：中途失败会留下可被扫描的半包。
- staging 放 `/tmp`：容器和多挂载环境可能跨文件系统，使原子移动失效。

## 5. 状态持久化、删除与来源

**Decision**

受管目录中的保留文件为：

```text
skills/<skill>/
├── SKILL.md
├── .oryxos-disabled     # 存在 = 管理员设置为 disabled
└── .oryxos-origin.yml   # 导入来源；手工维护的包可以没有
```

- 合法导入默认启用，不创建 disabled marker。禁用用 `CREATE_NEW` 原子创建 marker；启用前完整重校验，成功后删除 marker。
- `invalid` 始终从当前内容派生，不落第二份状态。对外状态优先级为：校验失败 `invalid`，否则 marker 存在为 `disabled`，否则 `enabled`。
- `.oryxos-origin.yml` 只记录 schemaVersion、`sourceType=upload`、清洗后的原文件名和 importedAt；无来源文件的手工包显示 `source=workspace`。
- 删除把启用、禁用或 invalid 包归档到 `.oryxos/archive/.skills/<agent>/<UTC>-<uuid>/`。先用 `NOFOLLOW_LINKS`/real-path containment 验证并创建 `.oryxos/archive/.skills/<agent>` 全父链，再写安全的 `archive.yml`（含原 directoryName），最后把包原子移动到该事件的 `package/`；Skill 名不参与归档路径，避免手工异常名称成为路径段；`.skills` 保留命名空间不会与合法 Agent 名冲突。
- 恢复 API 不在本期；归档内容不得进入运行时扫描。

**Rationale**

包内 marker 没有 Agent 级共享状态文件的并发覆盖风险，随包归档且保持 entry path 稳定。invalid 是内容当前是否合法，若持久化会在人工修复后留下陈旧状态。归档式删除延续项目已有 Agent 删除的可追溯语义。

**Alternatives considered**

- Agent 级 `.skills-state.yml`：每次变更都竞争同一文件，单点损坏影响全部 Skill。
- 移动到 disabled 目录：启停会改变 entry path，并引入活动/禁用目录的双重名称冲突。
- 物理删除：不可恢复，也不符合企业审计习惯。

## 6. 默认限制与超限行为

**Decision**

| 限制 | 默认值 |
|---|---:|
| 上传 ZIP 压缩大小 | 10 MiB |
| 总解压大小 | 25 MiB |
| 单文件大小 | 5 MiB |
| `SKILL.md` 大小 | 256 KiB |
| frontmatter 大小 | 64 KiB |
| YAML nesting depth | 8 |
| ZIP entry 数（含目录） | 128 |
| entry 路径深度 | 8 |
| entry 路径长度 | 512 字符 |
| 解压比 | 100:1 |
| 每 Agent 受管 Skill 数 | 64 |
| L1 目录字符预算 | 12,000 |
| 暂存事件 TTL | 24 小时 |

- Spring multipart 配置为 `max-file-size=10MB`、`max-request-size=11MB`，领域层仍独立执行所有硬限制。
- 管理导入和启用在写租约内模拟下一份目录；超过 Skill 数或 L1 预算时拒绝，不产生部分变更。
- 人工文件改动若造成超限，运行时按规范 Skill 名排序，只放入预算内的完整条目，绝不截断单项；记录被省略数量的 WARN，并在管理详情暴露 `catalogIncluded=false`/校验说明。
- 限制通过 `oryxos.skills.*` 绑定到装配层配置，再转换成 core 的纯 Java `SkillLimits`，不在 core 中读取 Spring Environment。

**Rationale**

管理路径应该保证“enabled 就可发现”；聚合预算必须在变更前检查。人工目录是唯一真相源，无法假设所有改动都经 API，因此运行时仍需要确定性、可观测的防线。

**Alternatives considered**

- 只依赖 multipart 上限：挡不住高解压比、单文件或 entry 数攻击。
- 随机删减 L1：不可复现，也会让同一目录表现不稳定。
- 截断 description：会改变作者提供的触发语义；按完整条目取舍更安全。

## 7. REST、管理台与错误模型

**Decision**

- 新建 `AgentSkillApiController`，资源根为 `/api/v1/agents/{agentName}/skills`：GET collection、POST multipart collection、GET member、PUT member `{enabled}`、DELETE member。
- 所有成功动作沿用项目惯例返回 HTTP 200 + `ApiResponse`；404 表示 Agent/Skill 不存在，409 表示名称冲突，400 表示格式/安全/状态校验失败，413 表示压缩或解压超限，未预期 I/O 返回不含内部细节的 500。
- multipart part 名固定为 `file`，Controller 用 `MultipartFile.getInputStream()` 交给 core，不调用 `getBytes()`；原文件名只作不可信来源展示。
- 管理台在 Agent 详情新增 `AgentSkillsTab.vue`，并增加薄的 `api/skills.js`；不引入 Router。服务端成功前不做乐观切换/删除，逐行 busy 防重复，删除二次确认，失败保留原状态。
- 新组件使用既有 token 并自带 scoped 样式；不顺带重构 App.vue 中所有旧 fetch。

**Rationale**

URI 与 PUT 开关方式都沿用现有 Agent/Schedule API；导入本质是创建 Agent 子资源，不需要 `/import` 动词。独立 Controller 和组件控制现有大文件继续膨胀，同时保持改动范围局部。

**Alternatives considered**

- JSON Base64：体积放大且造成额外堆内存复制。
- `POST /skills/import`：表达动作但偏离集合创建语义。
- 为一个页签引入 Vue Router 或重写全部 API client：超出本特性范围。

## 8. 结构化日志、测试与安全边界

**Decision**

- `SkillManagementService` 对每个已进入服务的 mutation 只记一条 SLF4J 2 fluent key-value 事件：`event=skill.management`、agent、skill、action、result、reasonCode；成功 INFO，领域拒绝 WARN，未预期失败 ERROR。multipart 超限、缺 part、坏 JSON/路径等在调用 service 前结束的 transport rejection 只走现有 Web 错误日志，不计作 `skill.management`。开发日志 pattern 增加 `%kvp`。
- 自动化覆盖 metadata parser、enabled-only snapshot、legacy 隔离、L1 无正文、快照只建一次、读写租约顺序、ZIP 攻击矩阵、原子失败无残留、重启状态、REST 400/404/409/413 与消息脱敏。
- Boot E2E 使用临时 `.oryxos` 和 mock provider 完成导入→发现→禁用→启用→删除；前端增加 Vitest + Vue Test Utils 的组件测试并保留 quickstart 浏览器验收。
- Skill 是代码/指令级信任边界。结构校验只保证包不会破坏文件系统，不证明正文或脚本善意；遵循 [Anthropic Agent Skills security guidance](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)，只导入可信来源并先审阅内容。
- disabled 的保证是从 OryxOS L1 发现和正常渐进式加载中排除。通用 `shell` 仍按现有“首 token 白名单”安全模型工作，本特性不声称提供操作系统级的逐路径强隔离。
- Session 当前只保存 role/content/toolName，无法可靠识别并删除某次 `read_file` 来自哪个 Skill。禁用/删除不追溯改写历史：后续 snapshot 和目录驱动的读取被阻止，但旧 Session 中已保存的正文/结果仍可能按历史窗口进入 prompt；负向验收使用新 Session，同时回归旧历史不被篡改。

**Rationale**

SLF4J key-value 可被现有 Logstash encoder 直接输出，又不让 core 依赖具体日志实现。测试重点放在最容易产生安全和一致性回归的边界。明确内容信任边界可以避免把 ZIP 安全误解成脚本沙箱。

**Alternatives considered**

- MDC：虚拟线程复用和异常分支更容易遗留上下文。
- 在 Controller 和 service 重复记日志：同一动作会形成两条不一致事件。
- 把禁用升级成 shell 命令全文路径解析：shell 语法无法靠轻量字符串解析形成可靠安全边界，且超出本特性。

## 9. 结论：无未决技术澄清

Phase 0 的所有技术问题已收敛：采用 Commons Compress 1.28.0 + JDK NIO；包内 disabled marker；请求级公平读写租约；固定安全预算；legacy 平铺文件保持 unmanaged。无需新增 Maven 模块或 SQLite 表。
