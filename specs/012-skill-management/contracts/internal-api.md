# Contract: Core Public Skill and Association APIs

类型名可按 Java 细节微调，但模块边界、单一真相源、锁顺序与失败语义不可改变。

## 1. 公共目录与安全解析

建议路径：`oryxos-core/src/main/java/io/oryxos/core/skill/`。

```java
public enum SkillStatus { ENABLED, DISABLED, INVALID }
public enum SkillSource { UPLOAD, GITHUB, WORKSPACE }
public enum LinkStatus { VALID, INVALID }

public record SkillMetadata(
    String name,
    String description,
    String version,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    Path entryPath) {}

public record ParsedSkill(SkillManifest manifest, String promptContent) {}

public record SkillManifest(
    String name,
    String description,
    String version,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    ActivationCriteria activation,
    GatingRequirements requires) {}

public record ActivationCriteria(
    List<String> keywords,
    List<String> excludeKeywords,
    List<String> patterns,
    List<String> tags,
    Integer maxContextTokens,
    String setupMarker) {}

public record GatingRequirements(
    List<String> bins,
    List<String> env,
    List<String> config,
    List<String> skills) {}

public interface SkillManifestParser {
  ParsedSkill parse(String content, boolean validateName);
}

public interface PublicSkillCatalog {
  List<PublicSkillDescriptor> list();
  PublicSkillDescriptor get(String skillName);
  PublicSkillDescriptor requireLoadable(String skillName);
}
```

`PublicSkillCatalog` 只扫描 `.oryxos/skills/<direct-child>` 的真实目录。含 `SKILL.md` 或保留 marker 即为受管候选；单项解析失败转 `INVALID`，不能使集合失败。公共包根、入口和所有后代使用 `NOFOLLOW_LINKS`、真实路径 containment 与资源限制校验。

`MarkdownFrontmatter`/`SkillMetadataReader` 收敛为唯一 parser：按 [parser-manifest.md](./parser-manifest.md) 规范化行尾/BOM/前导换行、定位 fence、执行 YAML 1.2 等价安全解析、legacy warning 和 name/version/activation/requires 校验。catalog 不缓存正文。现有 `SkillStore/SkillLoader/SkillRegistry/SkillService` 与 Agent-private `AgentSkillCatalog/SkillManagementService` 必须收敛为这一套公共包模型，不保留 eager body injection 的并行路径。

## 2. 关联服务

```java
public record SkillAssociation(
    String agentName,
    String skillName,
    Path linkPath,
    String rawTarget,
    LinkStatus linkStatus,
    SkillStatus skillStatus,
    boolean discoverable,
    SkillValidationError error) {}

public interface SkillAssociationService {
  List<SkillAssociation> list(String agentName);
  List<String> findLinkedAgents(String skillName);
  SkillAssociation associate(String agentName, String skillName);
  SkillAssociation unlink(String agentName, String skillName);
}
```

实现必须集中以下规则，Controller/AgentLifecycleService 不得复制：

- `expectedTarget(skill) = Path.of("..", "..", "..", "skills", skill)`，序列化为 `../../../skills/<skill>`；
- `associate` 在校验 Agent、公共包、父链和目标 path 后，以临时链接 + `ATOMIC_MOVE` 发布；相同标准链接幂等，任何其他占位返回冲突；
- `unlink` 先 `readSymbolicLink` 并逐字复验目标，再只删除链接 inode；非标准链接/真实目录安全失败；
- `list/findLinkedAgents` 不跟随错误链接；`findLinkedAgents` 每次遍历全部真实 Agent 目录，本期不得使用反向索引或缓存；
- disabled 公共包允许关联，invalid 包拒绝新关联；运行时只选择 valid link + enabled package。

## 3. 图谱锁与请求租约

```java
public interface SkillGraphCoordinator {
  SkillLease openRequest(String agentName);
  <T> T withGraphWrite(CheckedSupplier<T> action);
  <T> T withAgentMutation(String agentName, CheckedSupplier<T> action);
  <T> T withAgentsMutation(List<String> sortedAgents, CheckedSupplier<T> action);
}

public interface SkillLease extends AutoCloseable {
  SkillSnapshot snapshot();
  @Override void close();
}
```

锁顺序固定：全局 graph lock → 按规范名升序的 Agent locks；释放逆序。`openRequest` 取得 graph read + 当前 Agent read，锁内扫描标准链接并构建 snapshot，租约持有至 ReAct 与 session save 完成。全局 enable/disable/delete 使用 graph write；单 Agent associate/unlink 使用 graph write + Agent write；force delete 使用 graph write + 锁内扫描所得全部 Agent write。fair lock 防止写操作排队后新请求持续插队。

所有路径都用 try/finally 或 try-with-resources 释放；锁 registry 不删除 entry。`AgentLifecycleService.delete` 和会触及 `agents/<agent>/skills` 的 Workspace/Agent files API 必须复用 coordinator，不能形成写入旁路。

## 4. Runtime 接线

```java
public record SkillSnapshot(
    String agentName,
    Instant capturedAt,
    List<SkillMetadata> skills,
    int renderedChars,
    int omittedCount) {}

public String AgentService.process(Session session, String userMessage) {
  try (SkillLease lease = skillGraph.openRequest(session.profileName())) {
    Profile profile = profileRegistry.require(session.profileName());
    String reply = reActLoop.run(session, userMessage, profile, lease.snapshot());
    sessionManager.save(session);
    return reply;
  }
}

String ReActLoop.run(
    Session session, String userMessage, Profile profile, SkillSnapshot skills);
ProviderRequest PromptBuilder.build(
    Session session, Profile profile, SkillSnapshot skills);
String ContextLoader.load(Profile profile, SkillSnapshot skills);
```

L1 固定为元数据目录，例如：

```text
## Available Skills
Only metadata is loaded. Read the entry only when the task matches.

- name: weather
  description: 查询天气并给出出行建议
  entry: /resolved/.oryxos/agents/ops-agent/skills/weather/SKILL.md
```

入口可以是 Agent 链接路径，便于模型理解归属；`read_file` 执行前仍必须重新验证该链接是一层标准关联且最终文件位于公共包内。L1 不得包含正文、resources、origin、marker 或 `allowed-tools`。Profile 未授权 `read_file` 时不能自动扩权，应追加不可加载提示并 WARN。

删除 `ContextLoader.appendSkills(profile)` 通过 `Profile.skills/SkillRegistry` 全文注入的生产路径。旧 `AGENT.md skills:` 只允许兼容解析并 WARN，不进入 snapshot、API 或文件系统关联。

### 4.1 L2/L3 访问门禁与错误

```java
public interface SkillResourceAccessGuard {
  GuardedSkillResource authorize(
      SkillSnapshot snapshot,
      String toolName,
      Path requestedPath,
      Profile profile);
}
```

对 Agent `skills/` 入口或其后代的每次 Tool 调用，guard 必须按序：

1. 从请求路径识别直接 Skill link basename，并确认它属于本次不可变 snapshot；
2. `readSymbolicLink` 重新验证原始 target 精确等于标准相对值，且只跟随这一层到真实公共包；
3. 以 `NOFOLLOW_LINKS` 检查入口/资源，验证 normalize + real path 始终 containment 在该包，拒绝包内链接和特殊文件；
4. 验证 `read_file` 或 `shell` 已在当前 `AGENT.md` Profile 中显式授权；
5. 返回规范化内部路径后，仍交给既有 SandboxChecker、ToolExecutor 和 `tool_invocations` 审计。

稳定拒绝 reason code 至少为 `SKILL_NOT_IN_SNAPSHOT`、`INVALID_SKILL_LINK`、`SKILL_RESOURCE_OUTSIDE_PACKAGE`、`SKILL_RESOURCE_UNAVAILABLE` 与 `SKILL_TOOL_NOT_ALLOWED`。拒绝作为失败 `ToolResult` 回填当前 ReAct 并照常审计，不抛出导致整个 Agent/其它 Skill 不可用的未处理异常。运行时不得因错误自动改读公共绝对路径、自动加载 L3 或选择另一个 Skill。

`shell` 只允许执行 snapshot Skill 包内、通过上述 containment 的脚本，且仍受命令首 token 白名单。`allowed-tools`、activation、requires 只作为 manifest 信息，不参与授权。

## 5. 公共包管理

```java
public interface SkillPackageImporter {
  PreparedSkill prepare(InputStream zip, String originalFilename);
  void discard(PreparedSkill prepared);
}

public record DeleteResult(
    String skillName, boolean forced, List<String> affectedAgents, boolean archived) {}

public final class SkillInUseException extends RuntimeException {
  String skillName();
  List<String> linkedAgents();
  String reasonCode(); // SKILL_IN_USE
}

public interface PublicSkillManagementService {
  List<PublicSkillDescriptor> list();
  PublicSkillDescriptor get(String skillName);
  PublicSkillDescriptor importZip(InputStream zip, String originalFilename);
  PublicSkillDescriptor setEnabled(String skillName, boolean enabled);
  DeleteResult delete(String skillName, boolean force);
}
```

`prepare` 在 graph lock 外完成有界落盘、central-directory 检查、解压与完整校验。`importZip` 在 graph write 内重检同名冲突与 FileStore 后用 `ATOMIC_MOVE` 发布，finally 清理 staging。

`setEnabled` 在 graph write 内创建/移除公共 marker；enable 先复验全部内容。因为请求持 graph read，已开始请求的 L2/L3 文件不会在中途改变。

`delete(force=false)` 在 graph write 内扫描全部 Agent：非空则抛 `SkillInUseException` 且零副作用；为空则原子归档。`delete(force=true)` 在 graph write 内重新扫描，按序取得相关 Agent write locks，预检全部标准链接，解除全部标准链接并原子归档公共包；不得接受客户端传来的 Agent 列表作为执行依据。

## 6. 强制删除失败边界

force 删除跨多个文件系统路径，不宣称事务原子性。实现必须在开始 mutation 前完成包、归档目标和全部标准链接预检；同进程内任一步失败时，在仍持锁的情况下对本操作已移除且当前位置为空的链接尽力重建，并返回稳定错误与单条失败领域事件。不得覆盖外部占位，不得删除非标准链接或真实目录。

本期不提供持久化 operation journal、启动恢复或跨进程 crash consistency。重试时重新扫描实际文件系统；列表/API 必须如实显示悬空或异常链接，便于管理员诊断。

## 7. Agent 创建和生命周期

```java
AgentDescriptor AgentLifecycleService.create(CreateAgentCommand command);
// command.skills() = 要建立链接的公共 Skill 名单
```

创建前在 graph write 内验证 Agent 名、全部公共 Skill 与所有目标路径。创建 Agent 暂存目录、写 `AGENT.md`（无 `skills:`）、创建全部标准链接，再原子发布 Agent 目录；任何失败清理暂存，新 Agent 不可见。生成草稿不生成 `skills:`；`example` Skill 不创建。

删除 Agent 使用 graph write + Agent write，先归档完整 Agent 目录；公共包不受影响。generic files API 对 `skills/` 下软链接的覆盖、跟随或递归写入一律拒绝，关联只能走专用服务。

## 8. Web 与日志适配

- `SkillApiController` 只映射公共 list/get/multipart import/global state/normal+force delete。
- `AgentSkillApiController` 只映射实际关联 list/associate/unlink。
- 既有 `SkillAssociationApiController` 如保留只做 deprecated adapter，委托相同 service。
- `MultipartFile`、HTTP status 与 DTO 不进入 core；`GlobalExceptionHandler` 将 `SkillInUseException` 转 typed 409 data。

每个进入 service 的 mutation 恰写一条：

```text
event=skill.management
skill=<canonical>
agent=<canonical-if-single>
affectedAgents=<sorted-if-multiple>
action=import|associate|unlink|enable|disable|delete|force_delete
result=success|rejected|failed
reasonCode=<stable-code-if-non-success>
```

L2/L3 继续经 ToolExecutor 写既有 `tool_invocations`。不新增 `use_skill`、Skill Tool 注册或数据库状态表。
