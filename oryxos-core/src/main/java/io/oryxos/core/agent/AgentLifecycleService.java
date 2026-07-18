package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 生命周期编排（第 30 节）：三条录入（API create / WorkspaceWatcher 事件 / 启动扫描）都汇到同一段 {@link
 * #register(Path)}；创建脚手架、创建回滚、删除时序（注销定时 → 移索引 → 归档）都在这里串起来。
 *
 * <p>创建只需 name + description，后台按模板脚手架出**完整目录**（AGENT.md + scripts/ + skills/ +
 * REFERENCE.md，内容为模板），再注册。本类不产生 HTTP 语义：{@link #get} 返回 {@link Optional}，404 由 web 层决定（core 不反向依赖
 * web）；定义非法统一抛 {@code ProfileValidationException}（web 映射 400）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "协作者均为 Spring 注入的共享单例，构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class AgentLifecycleService {

  private static final String AGENT_MD_TEMPLATE =
      """
      ---
      name: {name}
      description: {description}
      identity:
        agent_name: {name}
        prompt: 你是一个乐于助人的助手。
      provider:
        name: {provider}
        model: 请在此填写模型名
      tools:
        - read_file
        - shell
        - notify
      bootstrap:
        - AGENTS.md
      settings:
        max_iterations: 10
        max_history_turns: 20
      ---

      在这里写这个 Agent 的任务指令（正文）。被触发时它会照做。
      - 较长的规范 / 清单放 skills/，正文指到时用 read_file 按需读；
      - 参考资料放 REFERENCE.md，拿不准时用 read_file 读；
      - 脚本放 scripts/，正文让 shell/python 跑，产出进上下文、代码不进。
      """;

  private static final String SCRIPT_TEMPLATE =
      """
      #!/usr/bin/env python3
      \"\"\"示例脚本：Agent 用 shell 跑它拿确定性数据；产出（stdout 的 JSON）进上下文、代码本身不进。\"\"\"
      import json
      import sys

      json.dump({"hello": "world"}, sys.stdout, ensure_ascii=False)
      """;

  private static final String SKILL_TEMPLATE =
      """
      # 示例子指令
      把较长的规范 / 清单 / 格式要求放这里。Agent 正文指到时，用底座的 read_file 把它读进上下文——平时不占常驻 prompt。
      """;

  private static final String REFERENCE_TEMPLATE =
      """
      # 参考资料
      把字段字典、已知边界、阈值、联系人放这里。Agent 拿不准某个细节时，用底座的 read_file 读它。
      """;

  /** 「用大模型生成 Agent 草稿」的系统说明：约束模型只吐一份可解析的 AGENT.md，不加解释、不加代码围栏。 */
  private static final String AGENT_AUTHOR_PROMPT =
      """
      你是 OryxOS 的 Agent 作者。根据下面的需求，产出一个可用的 Agent 定义，只输出「一个 AGENT.md 文件」的完整内容。
      要求：
      1. 以 YAML frontmatter 开头结尾（--- 与 ---），frontmatter 必须含 name、description、identity(agent_name/prompt)、\
      provider(name/model)、tools、settings(max_iterations/max_history_turns)；有定时需求就加 schedules。
      2. name 必须是「{name}」；provider.name 必须是「{provider}」；model 填该 provider 下合理的模型名。
      3. frontmatter 之后是正文（这个 Agent 的任务指令）。
      4. 只输出 AGENT.md 文本本身——不要任何解释、不要 Markdown 代码围栏（```）。
      需求：
      """;

  /** Markdown 代码围栏标记（模型偶尔会用 ``` 包住输出，剥掉它）。 */
  private static final String CODE_FENCE = "```";

  private final AgentLoader agentLoader;
  private final ProfileRegistry profileRegistry;
  private final AgentScheduler agentScheduler;
  private final AgentStore agentStore;
  private final ProviderService providerService;
  private final String defaultProvider;
  private final String authorProvider;
  private final String authorModel;

  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      String defaultProvider,
      String authorProvider,
      String authorModel) {
    this.agentLoader = agentLoader;
    this.profileRegistry = profileRegistry;
    this.agentScheduler = agentScheduler;
    this.agentStore = agentStore;
    this.providerService = providerService;
    this.defaultProvider = defaultProvider;
    this.authorProvider = authorProvider;
    this.authorModel = authorModel;
  }

  /**
   * 创建：只需 name + description，后台按模板脚手架出完整目录（AGENT.md + scripts/ + skills/ + REFERENCE.md）→ 派生注册。name
   * 冲突第一步就拒；中途失败回滚已写目录，不留半个 Agent。
   */
  public Profile create(String name, String description) {
    if (profileRegistry.exists(name)) {
      throw new IllegalArgumentException("Agent 已存在: " + name);
    }
    Path agentDir = agentStore.writeAll(name, scaffold(name, description));
    try {
      return register(agentDir);
    } catch (RuntimeException e) {
      agentStore.delete(agentDir); // 回滚：把已写的目录删回去
      throw e;
    }
  }

  private Map<String, String> scaffold(String name, String description) {
    String desc = description == null || description.isBlank() ? "描述这个 Agent 做什么" : description;
    String provider =
        defaultProvider == null || defaultProvider.isBlank() ? "deepseek" : defaultProvider;
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "AGENT.md",
        AGENT_MD_TEMPLATE
            .replace("{name}", name)
            .replace("{description}", desc)
            .replace("{provider}", provider));
    files.put("scripts/example.py", SCRIPT_TEMPLATE);
    files.put("skills/example.md", SKILL_TEMPLATE);
    files.put("REFERENCE.md", REFERENCE_TEMPLATE);
    return files;
  }

  /** 注册一个 Agent 目录——API create、WorkspaceWatcher 事件、启动扫描三条录入共用同一段代码（FR-009）。 */
  public Profile register(Path agentDir) {
    Profile profile;
    try {
      profile = agentLoader.deriveProfile(agentDir);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败: " + agentDir.getFileName(), e);
    }
    profileRegistry.register(profile);
    if (!profile.schedules().isEmpty()) {
      agentScheduler.registerProfile(profile);
    }
    return profile;
  }

  public Optional<Profile> get(String name) {
    return profileRegistry.get(name);
  }

  public Collection<Profile> list() {
    return profileRegistry.all();
  }

  /** 更新：覆写 AGENT.md；先注销旧定时、再注册新的（旧 cron 不会跟新 cron 一起跑）。 */
  public Profile update(String name, String agentMarkdown) {
    Profile old = profileRegistry.get(name).orElse(null);
    Path agentDir = agentStore.write(name, agentMarkdown);
    Profile updated;
    try {
      updated = agentLoader.deriveProfile(agentDir);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败: " + agentDir.getFileName(), e);
    }
    if (old != null) {
      agentScheduler.unregisterProfile(old);
    }
    profileRegistry.register(updated);
    if (!updated.schedules().isEmpty()) {
      agentScheduler.registerProfile(updated);
    }
    return updated;
  }

  /**
   * 用大模型按一句话需求生成一份 AGENT.md 草稿（30 节「生成/编辑 Agent」）：一次 LLM 调用（走既有 {@link ProviderService}，落 llm_calls
   * 审计）产出文本 → 校验能否解析成合法定义（非法抛 {@code ProfileValidationException} → 400）→ 原样返回 {relativePath:
   * content} 给前端预览可改；**不落盘、不注册**（保存另走 {@link #saveFiles}）。生成用的 provider/model 取 oryxos.author.*
   * 配置；输出 AGENT.md 里的 provider 若该 Agent 已存在则沿用其 provider（保持可跑），否则用作者 provider。
   */
  public Map<String, String> generateFiles(String name, String description) {
    String genProvider =
        authorProvider == null || authorProvider.isBlank()
            ? (defaultProvider == null || defaultProvider.isBlank() ? "deepseek" : defaultProvider)
            : authorProvider;
    if (authorModel == null || authorModel.isBlank()) {
      // 没有可用的生成模型：明确报错（web 映射 503），不向 OpenAI 兼容端点发 model=null
      throw new IllegalStateException("未配置生成用模型（oryxos.author.model），无法用大模型生成 Agent");
    }
    String outputProvider =
        profileRegistry.get(name).map(p -> p.provider().name()).orElse(genProvider);
    Profile genProfile =
        new Profile(
            "agent-author",
            null,
            null,
            new Profile.ProviderRef(genProvider, authorModel, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Profile.Settings.defaults());
    String prompt =
        AGENT_AUTHOR_PROMPT.replace("{name}", name).replace("{provider}", outputProvider)
            + description;
    String text =
        providerService.chat("agent-author-" + name, genProfile, ProviderRequest.of(prompt)).text();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("模型未返回内容"); // → 503
    }
    String agentMarkdown = stripCodeFences(text);
    agentLoader.parse(agentMarkdown, name); // 校验：解析不成合法定义就抛 ProfileValidationException（→400）
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", agentMarkdown);
    return files;
  }

  /**
   * 保存一组（可能被用户改过的）Agent 文件并即时生效：先校验 AGENT.md 可解析（非法 → 400，不写坏目录）→ 写入 → 覆写后重注册（schedules
   * 变更先注销旧句柄再注册新的，同 {@link #update}）。用于「生成/编辑 Agent」的保存与文件浏览器的多文件保存。
   */
  public Profile saveFiles(String name, Map<String, String> files) {
    String agentMarkdown = files == null ? null : files.get("AGENT.md");
    if (agentMarkdown == null || agentMarkdown.isBlank()) {
      throw new IllegalArgumentException("缺少 AGENT.md 内容");
    }
    agentLoader.parse(agentMarkdown, name); // 先校验再落盘：非法定义不写进目录
    Profile old = profileRegistry.get(name).orElse(null);
    Path agentDir = agentStore.writeAll(name, files);
    Profile updated;
    try {
      updated = agentLoader.deriveProfile(agentDir);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败: " + agentDir.getFileName(), e);
    }
    if (old != null) {
      agentScheduler.unregisterProfile(old);
    }
    profileRegistry.register(updated);
    if (!updated.schedules().isEmpty()) {
      agentScheduler.registerProfile(updated);
    }
    return updated;
  }

  /** 去掉模型可能多吐的 Markdown 代码围栏（```lang ... ```），只留里面的 AGENT.md 文本。 */
  private static String stripCodeFences(String text) {
    String trimmed = text.strip();
    if (!trimmed.startsWith(CODE_FENCE)) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    String body = firstNewline < 0 ? "" : trimmed.substring(firstNewline + 1);
    int lastFence = body.lastIndexOf(CODE_FENCE);
    return (lastFence < 0 ? body : body.substring(0, lastFence)).strip();
  }

  /** 删除：先注销定时 → 再移出注册表 → 再把整个目录归档（不物理删）。顺序不能反（窗口期 cron 触发空指针）。 */
  public void delete(String name) {
    Profile profile = profileRegistry.get(name).orElse(null);
    if (profile == null) {
      return; // 幂等；404 由 web 层在调用前判定
    }
    agentScheduler.unregisterProfile(profile);
    profileRegistry.remove(name);
    agentStore.archive(name);
  }

  /** WorkspaceWatcher 收到删除事件用：目录已被手工删，只注销 + 移索引，不归档。 */
  public void unregisterByDir(Path agentDir) {
    String name = String.valueOf(agentDir.getFileName());
    Profile profile = profileRegistry.get(name).orElse(null);
    if (profile == null) {
      return;
    }
    agentScheduler.unregisterProfile(profile);
    profileRegistry.remove(name);
  }
}
