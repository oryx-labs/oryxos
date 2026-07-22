# Contract: Core Skill APIs and Runtime Wiring

以下是实现阶段应保持的模块边界和建议签名。类型名可因 Java 细节微调，但职责、调用方向和一致性语义不可改变。

## 1. Core 值对象

路径：`oryxos-core/src/main/java/io/oryxos/core/skill/`

```java
public record SkillMetadata(
    String name,
    String description,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    Path entryPath,
    String relativeEntry) {}

public record SkillSnapshot(
    String agentName,
    Instant capturedAt,
    List<SkillMetadata> skills,
    int renderedChars,
    int omittedCount) {
  public static SkillSnapshot empty(String agentName) { ... }
}

public enum SkillStatus { ENABLED, DISABLED, INVALID }
public enum SkillSource { UPLOAD, WORKSPACE }
```

所有 collection 在构造时 defensive copy；对外不暴露可变 Path collection。

所属 Agent 身份由 `oryxos-core/.../agent/AgentName` 统一解析：安全字符规则沿用 AgentStore，目录 basename 必须精确等于 Profile.name，lock key 用 ASCII lower-case 防 case-insensitive alias。AgentStore/AgentLoader/Skill API 不得各自复制名称正则。

## 2. Frontmatter 与 catalog

```java
public final class MarkdownFrontmatter {
  public static ParsedFrontmatter read(Path file, long maxBytes) throws IOException;
}

public final class SkillMetadataReader {
  public SkillMetadata read(Path agentDir, Path skillDir, SkillLimits limits);
}

public final class AgentSkillCatalog {
  public List<SkillDescriptor> list(String agentName);
  public SkillDescriptor get(String agentName, String directoryName);
  public SkillSnapshot snapshot(String agentName);
  public void validateCanEnable(String agentName, String directoryName);
}
```

- `MarkdownFrontmatter.read` 只读到第二个 fence并返回有界 frontmatter 文本；`SkillMetadataReader` 用 SnakeYAML `SafeConstructor + LoaderOptions` 禁 custom tags/duplicate keys/aliases，限制 nesting/code points。`AgentMarkdown` 只复用 fence/string split，保留既有 Agent YAML 兼容行为并跑原回归。
- catalog 每次调用扫描 `agents/<agent>/skills/<direct-child>`，只把含 `SKILL.md` 或 OryxOS 保留 marker 的目录作为受管候选；其他目录按 legacy/unmanaged 忽略。不依赖 WorkspaceWatcher 或进程级内容 cache。
- 单项解析异常转成 `INVALID` descriptor；Agent 目录访问失败才使整个调用失败。catalog/detail 对包目录、入口和后代做 `NOFOLLOW_LINKS` + real-path containment：根 symlink 不跟随、不列出并 WARN；真实包内链接/特殊文件标 invalid，均绝不进入 snapshot。
- snapshot 只选 `ENABLED && catalogIncluded`，按 name 排序。热路径为有界安全扫描：frontmatter fence、后代 `lstat`/size 和每文件最多 512-byte magic prefix；不得全文读取正文/resource。

## 3. 锁与请求 lease

```java
public final class AgentSkillLockRegistry {
  public <T> T withReadLock(String agentName, CheckedSupplier<T> work);
  public <T> T withWriteLock(String agentName, CheckedSupplier<T> work);
}

public final class SkillLease implements AutoCloseable {
  public SkillSnapshot snapshot();
  @Override public void close();
}

public final class AgentSkillCoordinator {
  public SkillLease openRequest(String agentName);
  public <T> T mutate(String agentName, CheckedSupplier<T> operation);
}
```

- registry 锁键必须使用 `AgentName.lockKey()`；`openRequest` 在取得读锁后确认 Agent 目录仍存在且 basename=Profile.name，避免 Agent 删除与 Profile 查询之间的竞态。
- 使用公平 `ReentrantReadWriteLock(true)`；不删除 map entry。
- `SkillLease.close()` 幂等且必须在 `finally`/try-with-resources 执行。

## 4. Runtime 签名变化

```java
// AgentService（保持现有 public entry，不新造并行入口）
public String process(Session session, String userMessage) {
  try (SkillLease lease = skillCoordinator.openRequest(session.profileName())) {
    Profile profile = profileRegistry.get(session.profileName()).orElseThrow(...);
    ProfileContext.set(profile);
    try {
      String reply = reActLoop.run(session, userMessage, profile, lease.snapshot());
      sessionManager.save(session);
      return reply;
    } finally {
      ProfileContext.clear();
    }
  }
}

// ReActLoop
public String run(
    Session session, String userMessage, Profile profile, SkillSnapshot skills);

// PromptBuilder
public ProviderRequest build(Session session, Profile profile, SkillSnapshot skills);

// ContextLoader
public String load(Profile profile, SkillSnapshot skills);
```

兼容性：必要时保留仅测试用/旧调用的重载并委托 `SkillSnapshot.empty(profile.name())`，但生产装配必须走显式 snapshot。不得新增 ThreadLocal 来传递 snapshot，也不得把 snapshot 持久化到 Session/Profile；既有 `ProfileContext` 生命周期保持不变。

L1 渲染固定格式：

```text
## Available Skills
Only metadata is loaded. When relevant, call read_file with the entry path.

- name: weather
  description: 查询天气并给出出行建议
  entry: /resolved/.oryxos/agents/ops/skills/weather/SKILL.md
```

不得包含正文、resource 内容、origin、marker 或 `allowed-tools`。Profile 未声明 `read_file` 时仍可显示目录，但追加明确的不可加载提示并记录一次 WARN；不得自动扩权。

## 5. 导入和管理

```java
public final class SkillPackageImporter {
  public PreparedSkill prepare(InputStream zip, String originalFilename);
  public void discard(PreparedSkill prepared);
}

public final class SkillManagementService {
  public List<SkillDescriptor> list(String agentName);
  public SkillDescriptor get(String agentName, String directoryName);
  public SkillDescriptor importSkill(
      String agentName, InputStream zip, String originalFilename);
  public SkillDescriptor setEnabled(String agentName, String directoryName, boolean enabled);
  public void delete(String agentName, String directoryName);
}
```

`prepare` 在写锁外完成压缩流落盘、central-directory 校验、解压、metadata/资源校验和来源文件生成。`importSkill` 在写锁内重检 Agent、所有状态下的同名冲突、Skill/L1 聚合预算和 FileStore，然后只做 `ATOMIC_MOVE`。所有分支最终清理 staging。

`setEnabled` 与 `delete` 整个状态切换在写锁内。`AgentLifecycleService.delete`、`AgentLifecycleService.saveFiles`/`AgentStore.writeAll` 和 WorkspaceApi 对受管 Skill 路径的写操作也必须复用同一 registry，并用临时文件 + 原子替换提交，不能形成旁路。

`SkillManagementService.list/get` 必须在同一 Agent 的短读锁内完成整次 catalog 扫描、资源统计和 descriptor/DTO 输入快照，避免与 delete、marker 切换或 files API 原子替换交错；方法返回后立即释放，不持锁到 HTTP 序列化。

## 6. 装配与配置

装配路径：`oryxos-cli/src/main/java/io/oryxos/cli/`。

- 新增 `SkillProperties` 绑定 `oryxos.skills.package-limits.*`、`oryxos.skills.catalog.*`、`oryxos.skills.staging-ttl`；`package-limits` 避免把 Java 关键字 `import` 作为配置字段名。启动时验证并转换为 `SkillLimits`。
- `OryxOsRuntime` 装配 lock registry、metadata reader、catalog、importer、management service、coordinator；AgentService 注入 coordinator。
- Apache Commons Compress 1.28.0 只加到 `oryxos-core`；根 pom 统一版本。
- 不新建 `oryxos-skill` 模块，不修改 `OryxTool`，不向 ToolRegistry 注册 Skill。

## 7. Web 适配

```java
@RestController
@RequestMapping("/api/v1/agents/{agentName}/skills")
public final class AgentSkillApiController { ... }
```

Controller 只做 HTTP/DTO 映射；`MultipartFile` 不进入 core。使用 `getInputStream()` 同步交给 `SkillManagementService`。领域异常由 GlobalExceptionHandler 翻译，不在 Controller 重复 catch 或记录管理日志。

## 8. 审计与日志

- L2/L3 仍经 ToolExecutor，因此继续写现有 `tool_invocations`；不新增旁路审计表。
- 管理服务对每个已进入 service 的变更调用恰一条 fluent key-value 日志；进入 service 前的 Web transport rejection 不属于该计数：

```text
event=skill.management
agent=<canonical>
skill=<canonical|unresolved>
action=import|enable|disable|delete
result=success|rejected|failed
reasonCode=<stable-code-if-failed>
```

- 日志不得包含正文、上传 bytes、绝对路径、API key 或未清洗异常消息。
