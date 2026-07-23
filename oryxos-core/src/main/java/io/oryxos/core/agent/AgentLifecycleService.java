package io.oryxos.core.agent;

import io.oryxos.core.OryxTool;
import io.oryxos.core.mcp.McpServerAdmin;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.skill.Skill;
import io.oryxos.core.skill.SkillRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
      - 脚本放 scripts/，正文让 shell/python 跑，产出进上下文、代码不进；
      - 任务产出的文件（研报 / 汇总 / 导出）用 write_file 写到本 Agent 的 output/ 目录，便于在管理台查看与下载。
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

  /** 每个 Agent 的产出目录说明：任务落盘的报告 / 汇总 / 导出放这里，可在管理台「输出」tab 查看与下载。 */
  private static final String OUTPUT_README_TEMPLATE =
      """
      # 产出目录（output/）
      这个 Agent 每次任务产出的文件（如每日研报、汇总、导出）用 write_file 写到本目录，
      文件名建议带日期（如 report-2026-07-23.md），便于在管理台「输出」tab 查看与下载。
      本说明文件可删。
      """;

  /** 「用大模型生成 Agent 草稿」的系统说明：约束模型只吐一份可解析的 AGENT.md，不加解释、不加代码围栏。 */
  // 作者提示词模板：{name}/{provider} 是目标 Agent 的名字与 provider；{tools}/{channels} 是运行时真实能力
  // （避免模型编造平台没有的工具/渠道）；{example} 是一份字段正确的范例做 few-shot。
  private static final String AGENT_AUTHOR_PROMPT =
      """
      你是 OryxOS 的 Agent 作者。根据末尾的「需求」，产出一个**可直接运行**的 Agent（至少一个 AGENT.md，必要时附带脚本/子指令）。

      硬性规则：
      1. AGENT.md 以 YAML frontmatter 开头结尾（--- 与 ---），frontmatter 必须含 name、description、identity(agent_name/prompt)、\
      provider(name/model)、tools、settings(max_iterations/max_history_turns)；有定时需求再加 schedules。
      2. name 必须是「{name}」；provider.name 必须是「{provider}」；model 填该 provider 下合理的模型名。
      3. tools 只能从下面【可用工具】里按需挑选，**绝不允许编造清单以外的工具名**。常见映射：查网页/接口数据用 http_get / http_post；\
      抓网页正文用 fetch_webpage；读写文件用 read_file / write_file；跑脚本用 shell。
      4. schedules 每条的字段只有：id（任务标识）、cron（Spring 6 段 cron，如 "0 0 9 * * *" 表示每天 9 点）、zone（时区，如 Asia/Shanghai）、\
      message（到点发给 Agent 的触发语）。**不要用 timezone、action 等清单外字段名。**
      5. 通知：{notify}
      6. MCP：如果任务需要用到下面【可用 MCP Server】里"已连接"的某个 server 提供的能力，把该 server 名加进 frontmatter 的 \
      mcp_servers 列表，**并且**把它提供的具体工具名也加进 tools 列表（两者都要写，只写一个不生效）；未连接 / 清单外的 server \
      不要选、不要编造。没有需要就不加 mcp_servers 字段。
      7. Skill（用全局能力库约束产出）：下面【可用 Skill】里每个 Skill 是一段约束产出质量/格式的规范。若需求命中某个 Skill 覆盖的场景\
      （如"产出报告/研报/日报"就用 report-format），把该 Skill 名加进 frontmatter 的 skills 列表（可多个）——这些 Skill 正文会在\
      运行时注入 system prompt 来强约束 Agent 的产出。没有合适的就不加 skills 字段；**绝不编造清单以外的 Skill 名**。{required_skills}
      8. 你可以按需**额外产出文件**——脚本放 scripts/<名>、参考资料放 REFERENCE.md。要不要、要几个由你按需求决定；\
      例如需要抓 GitHub 榜单，就写一个 scripts/xxx.py 并在 AGENT.md 正文里用 shell 调它。（约束产出的规范优先用全局 Skill，不必再写 skills/ 子文件。）
      9. 输出格式：多个文件时，每个文件前**单独一行**写分隔符 `===FILE: <相对路径>===`（第一个必须是 AGENT.md）；\
      若只需要 AGENT.md 可不用分隔符直接输出它。不要用 Markdown 代码围栏（```）包整个输出，也不要任何额外解释。

      【可用工具】（tools 只能从这里选，禁止编造）
      {tools}

      【可用 MCP Server】（mcp_servers 只能从这里选"已连接"的，禁止编造）
      {mcp_servers}

      【可用 Skill】（skills 只能从这里选，禁止编造）
      {skills}

      【正确示例（仅示范字段与工具用法，name/provider 以上面规则为准）】
      {example}

      需求：
      """;

  /** few-shot 范例：真实工具（http_get + notify）+ 正确 schedules 字段（id/cron/zone/message）。 */
  private static final String AUTHOR_EXAMPLE =
      """
      ---
      name: demo-weather
      description: 每天早上查询天气并把提示发到团队群
      identity:
        agent_name: 天气助手
        prompt: 你是一个天气播报助手，简洁给出天气与穿搭提示。
      provider:
        name: deepseek
        model: deepseek-chat
      tools:
        - http_get
        - notify
      settings:
        max_iterations: 10
        max_history_turns: 20
      schedules:
        - id: morning-weather
          cron: "0 0 9 * * *"
          zone: Asia/Shanghai
          message: 查询今天的天气并把穿搭提示发到团队群
      ---

      被触发时：用 http_get 调用天气接口获取今天天气，整理成一句话穿搭提示，再用 notify 发到 team-lark 渠道。""";

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
  // 生成 Agent 时注入提示词的真实运行时能力：工具清单（启动固定）+ notify 渠道注册表（运行时可变，按需查）
  private final Map<String, OryxTool> tools;
  private final NotifyChannelRegistry notifyChannels;
  // 31 节：生成时把已连接 MCP server 目录喂给作者模型，让它自己判断要不要挂、挂哪个（可空——旧调用方不带这个能力）
  private final McpServerAdmin mcpServerAdmin;
  // 32 节：生成时把全局 Skill 库目录喂给作者模型，让它按需把 Skill 加进 frontmatter 的 skills（可空——旧调用方不带）
  private final SkillRegistry skillRegistry;

  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      String defaultProvider,
      String authorProvider,
      String authorModel,
      Map<String, OryxTool> tools,
      NotifyChannelRegistry notifyChannels) {
    this(
        agentLoader,
        profileRegistry,
        agentScheduler,
        agentStore,
        providerService,
        defaultProvider,
        authorProvider,
        authorModel,
        tools,
        notifyChannels,
        null);
  }

  /** 31 节：注入 {@link McpServerAdmin}，生成提示词里补上真实的"可用 MCP Server"清单。 */
  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      String defaultProvider,
      String authorProvider,
      String authorModel,
      Map<String, OryxTool> tools,
      NotifyChannelRegistry notifyChannels,
      McpServerAdmin mcpServerAdmin) {
    this(
        agentLoader,
        profileRegistry,
        agentScheduler,
        agentStore,
        providerService,
        defaultProvider,
        authorProvider,
        authorModel,
        tools,
        notifyChannels,
        mcpServerAdmin,
        null);
  }

  /** 32 节：注入 {@link SkillRegistry}，生成提示词里补上真实的"可用 Skill"清单，并允许显式指定必启用的 Skill。 */
  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      String defaultProvider,
      String authorProvider,
      String authorModel,
      Map<String, OryxTool> tools,
      NotifyChannelRegistry notifyChannels,
      McpServerAdmin mcpServerAdmin,
      SkillRegistry skillRegistry) {
    this.agentLoader = agentLoader;
    this.profileRegistry = profileRegistry;
    this.agentScheduler = agentScheduler;
    this.agentStore = agentStore;
    this.providerService = providerService;
    this.defaultProvider = defaultProvider;
    this.authorProvider = authorProvider;
    this.authorModel = authorModel;
    this.tools = tools;
    this.notifyChannels = notifyChannels;
    this.mcpServerAdmin = mcpServerAdmin;
    this.skillRegistry = skillRegistry;
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
    files.put("output/README.md", OUTPUT_README_TEMPLATE); // 建出产出目录（writeAll 建不了空目录，用占位说明落地）
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
  public Map<String, String> generateFiles(String name, String description, String notifyChannel) {
    return generateFiles(name, description, notifyChannel, List.of());
  }

  /**
   * 同上，但允许用户在前端**显式指定必须启用的 Skill**（{@code requiredSkills}）：这些 Skill 会作为硬指令写进作者提示词，让生成的 AGENT.md 的
   * skills 列表务必包含它们。传空列表则完全由作者模型按需自选。指定了不存在的 Skill → 400。
   */
  public Map<String, String> generateFiles(
      String name, String description, String notifyChannel, List<String> requiredSkills) {
    String genProvider =
        authorProvider == null || authorProvider.isBlank()
            ? (defaultProvider == null || defaultProvider.isBlank() ? "deepseek" : defaultProvider)
            : authorProvider;
    if (authorModel == null || authorModel.isBlank()) {
      // 没有可用的生成模型：明确报错（web 映射 503），不向 OpenAI 兼容端点发 model=null
      throw new IllegalStateException("未配置生成用模型（oryxos.author.model），无法用大模型生成 Agent");
    }
    // notify 目标由用户在前端手动选（"投递到哪里"是人的决定，不让模型猜）；选了就校验渠道确实存在
    String channel = notifyChannel == null ? "" : notifyChannel.strip();
    if (!channel.isEmpty() && notifyChannels != null && !notifyChannels.exists(channel)) {
      throw new IllegalArgumentException("通知渠道不存在: " + channel);
    }
    // 用户显式指定的 Skill（"启用哪个 Skill"也是人的决定）：校验确实存在于全局库
    List<String> required = requiredSkills == null ? List.of() : requiredSkills;
    for (String s : required) {
      if (skillRegistry == null || !skillRegistry.exists(s)) {
        throw new IllegalArgumentException("Skill 不存在: " + s);
      }
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
        AGENT_AUTHOR_PROMPT
                .replace("{name}", name)
                .replace("{provider}", outputProvider)
                .replace("{tools}", describeTools())
                .replace("{mcp_servers}", describeMcpServers())
                .replace("{skills}", describeSkills())
                .replace("{required_skills}", requiredSkillsDirective(required))
                .replace("{notify}", notifyDirective(channel))
                .replace("{example}", AUTHOR_EXAMPLE)
            + description;
    String text =
        providerService.chat("agent-author-" + name, genProfile, ProviderRequest.of(prompt)).text();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("模型未返回内容"); // → 503
    }
    // 多文件解析（模型自己决定要不要脚本/子指令）：按 ===FILE: path=== 切分；无分隔符则整段当 AGENT.md
    Map<String, String> files = parseGeneratedFiles(text);
    String agentMarkdown = files.get("AGENT.md");
    if (agentMarkdown == null || agentMarkdown.isBlank()) {
      throw new IllegalArgumentException("生成结果缺少 AGENT.md");
    }
    // 确定性兜底：用户勾选的 Skill 必须出现在 frontmatter 的 skills 里——模型漏写就在这里补齐（不靠模型自觉）
    agentMarkdown = ensureRequiredSkills(agentMarkdown, required);
    files.put("AGENT.md", agentMarkdown);
    agentLoader.parse(agentMarkdown, name); // 校验：解析不成合法定义就抛 ProfileValidationException（→400）
    return files;
  }

  /**
   * 把 {@code required} 里用户勾选的 Skill 并进 AGENT.md frontmatter 的 {@code skills}
   * 列表：模型已写的保留，缺的补上（去重、模型选的排前）。 只重写 skills 一个块，其余 frontmatter 原样保留。required 为空或全已在则原样返回。
   */
  static String ensureRequiredSkills(String agentMarkdown, List<String> required) {
    if (required == null || required.isEmpty()) {
      return agentMarkdown;
    }
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(agentMarkdown);
    List<String> existing = skillsFromFrontmatter(parsed.frontmatter());
    if (existing.containsAll(required)) {
      return agentMarkdown; // 模型已把勾选的都写进去了，无需改动
    }
    List<String> merged = new ArrayList<>(existing);
    for (String s : required) {
      if (!merged.contains(s)) {
        merged.add(s);
      }
    }
    // 定位 frontmatter 围栏（合法定义必有；找不到则不动，交由后续校验报错）
    String normalized = agentMarkdown.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    if (lines.length == 0 || !"---".equals(lines[0].strip())) {
      return agentMarkdown;
    }
    int close = -1;
    for (int i = 1; i < lines.length; i++) {
      if ("---".equals(lines[i].strip())) {
        close = i;
        break;
      }
    }
    if (close < 0) {
      return agentMarkdown;
    }
    // 保留 frontmatter 里除 skills 块以外的行（skills: 顶层键及其后续缩进的列表项一并剔除）
    List<String> kept = new ArrayList<>();
    for (int i = 1; i < close; i++) {
      String line = lines[i];
      boolean topLevelSkills =
          !line.isEmpty()
              && !Character.isWhitespace(line.charAt(0))
              && line.strip().matches("skills\\s*:.*");
      if (topLevelSkills) {
        i++;
        while (i < close && (lines[i].startsWith(" ") || lines[i].startsWith("\t"))) { // 跳过其列表项/缩进块
          i++;
        }
        i--; // 抵消 for 的 i++
        continue;
      }
      kept.add(line);
    }
    StringBuilder out = new StringBuilder("---\n");
    for (String line : kept) {
      out.append(line).append('\n');
    }
    out.append("skills:\n");
    for (String s : merged) {
      out.append("  - ").append(s).append('\n');
    }
    out.append("---\n");
    for (int i = close + 1; i < lines.length; i++) {
      out.append(lines[i]);
      if (i < lines.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  private static List<String> skillsFromFrontmatter(Map<String, Object> frontmatter) {
    Object value = frontmatter.get("skills");
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      if (item != null) {
        out.add(String.valueOf(item));
      }
    }
    return out;
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

  /** 把注册表里真实的工具（name + 一行描述）铺给作者模型，杜绝编造清单外的工具名。 */
  private String describeTools() {
    if (tools == null || tools.isEmpty()) {
      return "（当前无可用工具）";
    }
    return tools.values().stream()
        .sorted(Comparator.comparing(OryxTool::getName))
        .map(t -> "- " + t.getName() + "：" + oneLine(t.getDescription()))
        .collect(Collectors.joining("\n"));
  }

  /** 把已配置 MCP server 的连接状态（+ 它提供的工具名）铺给作者模型；未连接的照样列出但标注不可用，杜绝模型选一个连不上的 server。 */
  private String describeMcpServers() {
    if (mcpServerAdmin == null) {
      return "（当前无可用 MCP server）";
    }
    List<McpServerStatus> statuses = mcpServerAdmin.status();
    if (statuses.isEmpty()) {
      return "（当前无可用 MCP server）";
    }
    return statuses.stream()
        .map(
            s ->
                "- "
                    + s.name()
                    + "："
                    + (s.connected()
                        ? "已连接，提供工具 " + String.join(", ", s.toolNames())
                        : "未连接（" + oneLine(s.error()) + "），暂不可用，不要选"))
        .collect(Collectors.joining("\n"));
  }

  /** 把全局 Skill 库（名+描述）铺给作者模型；模型据此按需把 Skill 加进 frontmatter 的 skills，禁止编造清单外的名字。 */
  private String describeSkills() {
    if (skillRegistry == null || skillRegistry.all().isEmpty()) {
      return "（当前无可用 Skill）";
    }
    return skillRegistry.all().stream()
        .sorted(Comparator.comparing(Skill::name))
        .map(s -> "- " + s.name() + "：" + oneLine(s.description()))
        .collect(Collectors.joining("\n"));
  }

  /** 用户显式指定必启用的 Skill：作为硬指令追加到 skills 规则末尾；没指定则为空。目标由人定，不让模型漏。 */
  private static String requiredSkillsDirective(List<String> required) {
    if (required == null || required.isEmpty()) {
      return "";
    }
    return "【用户已指定：frontmatter 的 skills 列表必须包含这些 Skill】" + String.join("、", required);
  }

  /** 通知指令：用户选了渠道就固定投递到它（唯一目标）；没选就明确禁止 notify。目标由人定，不让模型猜。 */
  private static String notifyDirective(String channel) {
    if (channel == null || channel.isBlank()) {
      return "本 Agent 不配置通知渠道——tools 里不要包含 notify，正文也不要提通知。";
    }
    return "本 Agent 需要对外发送结果时，一律用 notify 工具发到渠道「"
        + channel
        + "」（唯一允许目标，不要用别的渠道、不要编造）；正文里请明确写「发到 "
        + channel
        + "」。";
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').strip();
  }

  /** 文件分隔符：模型多文件输出时每个文件前单独一行 {@code ===FILE: <相对路径>===}。 */
  private static final String FILE_MARKER_PREFIX = "===FILE:";

  private static final String NEWLINE = "\n";

  private static final java.util.regex.Pattern FILE_MARKER =
      java.util.regex.Pattern.compile("^===FILE:\\s*(.+?)\\s*===\\s*$");

  /**
   * 解析作者模型输出的多文件文本：按 {@code ===FILE: path===} 分隔切成 {@code 路径→内容}；无分隔符则整段作为 AGENT.md（向后兼容）。
   * 每个文件内容各自剥 Markdown 代码围栏。
   */
  private static Map<String, String> parseGeneratedFiles(String text) {
    Map<String, String> files = new LinkedHashMap<>();
    String stripped = text.strip();
    if (!stripped.contains(FILE_MARKER_PREFIX)) {
      files.put("AGENT.md", stripCodeFences(stripped));
      return files;
    }
    String current = null;
    StringBuilder buf = new StringBuilder();
    for (String line : stripped.split(NEWLINE, -1)) {
      java.util.regex.Matcher m = FILE_MARKER.matcher(line.strip());
      if (m.matches()) {
        if (current != null) {
          files.put(current, stripCodeFences(buf.toString()));
        }
        current = m.group(1).strip();
        buf.setLength(0);
      } else if (current != null) {
        buf.append(line).append('\n');
      }
      // 分隔符之前的前言（若有）忽略
    }
    if (current != null) {
      files.put(current, stripCodeFences(buf.toString()));
    }
    return files;
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
