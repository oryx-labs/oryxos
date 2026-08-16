package io.oryxos.core.agent;

import io.oryxos.core.OryxTool;
import io.oryxos.core.mcp.McpServerAdmin;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.BoundSkillDescriptor;
import io.oryxos.core.skill.InstalledSkillCatalog;
import io.oryxos.core.skill.SkillCatalog;
import io.oryxos.core.skill.SkillCatalogEntry;
import io.oryxos.core.skill.SkillRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;

/**
 * Agent 生命周期编排（第 30 节）：三条录入（API create / WorkspaceWatcher 事件 / 启动扫描）都汇到同一段 {@link
 * #register(Path)}；创建脚手架、创建回滚、删除时序（注销定时 → 移索引 → 归档）都在这里串起来。
 *
 * <p>创建只需 name + description，后台按模板脚手架出 Agent 自有文件；共享 Skill 仅通过 {@code skills/<name>} 软连接绑定。本类不产生
 * HTTP 语义：{@link #get} 返回 {@link Optional}，404 由 web 层决定（core 不反向依赖 web）；定义非法统一抛 {@code
 * ProfileValidationException}（web 映射 400）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "协作者均为 Spring 注入的共享单例，构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class AgentLifecycleService {

  private static final String PARENT_PATH_SEGMENT = "..";

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
        model: {model}
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
      - 已绑定 Skill 的 name、description 与本地 SKILL.md 入口会自动列入提示；需要时再用 read_file 按需读正文；
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
      2. name 必须是「{name}」；provider.name 必须是「{provider}」；model 必须是「{model}」（若该 provider 下没有这个 exact 模型名，就填该 provider 下合理的默认模型名）。
      3. tools 只能从下面【可用工具】里按需挑选，**绝不允许编造清单以外的工具名**。常见映射：查网页/接口数据用 http_get / http_post；\
      抓网页正文用 fetch_webpage；读写文件用 read_file / write_file；跑脚本用 shell。
      4. schedules 每条必须包含：key（Agent 内唯一的配置键）、name（展示名称）、cron（Spring 6 段 cron，如 "0 0 9 * * *" 表示每天 9 点）、\
      zone（时区，如 Asia/Shanghai）、message（到点发给 Agent 的触发语）。不要输出 legacy id、timezone、action 等字段。
      5. 通知：{notify}
      6. MCP：如果任务需要用到下面【可用 MCP Server】里"已连接"的某个 server 提供的能力，把该 server 名加进 frontmatter 的 \
      mcp_servers 列表，**并且**把它提供的具体工具名也加进 tools 列表（两者都要写，只写一个不生效）；未连接 / 清单外的 server \
      不要选、不要编造。没有需要就不加 mcp_servers 字段。
      7. Skill（用共享能力库约束产出）：下面【可用 Skill】只提供候选元数据。若需求命中某个 Skill 覆盖的场景，允许在本次生成的\
      AGENT.md frontmatter 暂时输出 skills 列表作为**生成建议 sidecar**；后端会立即移除该字段，最终 AGENT.md 绝不保存 skills。\
      Skill 正文不会预载，运行时只展示绑定 Skill 的元数据和本地入口，再由 Agent 用 read_file 按需读取。**绝不编造清单外名称**。{required_skills}
      8. 你可以按需**额外产出 Agent 自有文件**——脚本放 scripts/<名>、参考资料放 REFERENCE.md。绝不产出 skills/**；\
      例如需要抓 GitHub 榜单，就写一个 scripts/xxx.py 并在 AGENT.md 正文里用 shell 调它。
      9. 输出格式：多个文件时，每个文件前**单独一行**写分隔符 `===FILE: <相对路径>===`（第一个必须是 AGENT.md）；\
      若只需要 AGENT.md 可不用分隔符直接输出它。不要用 Markdown 代码围栏（```）包整个输出，也不要任何额外解释。

      【可用工具】（tools 只能从这里选，禁止编造）
      {tools}

      【可用 MCP Server】（mcp_servers 只能从这里选"已连接"的，禁止编造）
      {mcp_servers}

      【可用 Skill】（仅用于生成建议 sidecar，禁止编造）
      {skills}

      【正确示例（仅示范字段与工具用法，name/provider 以上面规则为准）】
      {example}

      需求：
      """;

  /** few-shot 范例：真实工具（http_get + notify）+ 正确 schedules 字段（key/name/cron/zone/message）。 */
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
        model: deepseek-v4-flash
      tools:
        - http_get
        - notify
      settings:
        max_iterations: 10
        max_history_turns: 20
      schedules:
        - key: morning-weather
          name: Morning weather
          cron: "0 0 9 * * *"
          zone: Asia/Shanghai
          message: 查询今天的天气并把穿搭提示发到团队群
      ---

      被触发时：用 http_get 调用天气接口获取今天天气，整理成一句话穿搭提示，再用 notify 发到 team-lark 渠道。""";

  /** Markdown 代码围栏标记（模型偶尔会用 ``` 包住输出，剥掉它）。 */
  private static final String CODE_FENCE = "```";

  /** YAML frontmatter 围栏（与 AgentMarkdown 一致）。 */
  private static final String YAML_FENCE = "---";

  /** YAML 层级的缩进字面量。 */
  private static final String INDENT_SPACE = " ";

  private static final String INDENT_TAB = "\t";

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
  // 32 节：生成时把 catalog 元数据喂给作者模型，模型的临时 skills 建议会转成 sidecar 后从 AGENT.md 删除。
  private final SkillRegistry skillRegistry;
  private final AgentSkillBindingService skillBindings;
  private final SkillCatalog skillCatalog;

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
        skillRegistry,
        null,
        skillRegistry == null ? null : new InstalledSkillCatalog(skillRegistry));
  }

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
      SkillRegistry skillRegistry,
      AgentSkillBindingService skillBindings,
      SkillCatalog skillCatalog) {
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
    this.skillBindings = skillBindings;
    this.skillCatalog = skillCatalog;
  }

  /**
   * 创建：只需 name + description，后台按模板脚手架出 Agent 自有文件并单独提交 Skill 绑定。name 冲突第一步就拒；中途失败回滚已写目录，不留半个 Agent。
   */
  public Profile create(String name, String description) {
    return create(name, description, null, null, List.of());
  }

  /**
   * 创建：name + description + 可选 provider/model；provider/model 写进 AGENT.md 的 frontmatter，新建时即可选模型。
   * provider/model 为空则回退默认 provider（oryxos.agent.default-provider）与占位模型名。
   */
  public Profile create(String name, String description, String provider, String model) {
    return create(name, description, provider, model, List.of());
  }

  public Profile create(String name, String description, List<String> initialSkills) {
    return create(name, description, null, null, initialSkills);
  }

  /** 创建 Agent 并原子写入其初始 Skill 绑定。 */
  public Profile create(
      String name, String description, String provider, String model, List<String> initialSkills) {
    if (profileRegistry.exists(name)) {
      throw new IllegalArgumentException("Agent 已存在: " + name);
    }
    String resolvedProvider =
        (provider == null || provider.isBlank())
            ? (defaultProvider == null || defaultProvider.isBlank() ? "deepseek" : defaultProvider)
            : provider;
    String resolvedModel = (model == null || model.isBlank()) ? "请在此填写模型名" : model;
    Path agentDir =
        agentStore.writeAll(name, scaffold(name, description, resolvedProvider, resolvedModel));
    Profile profile = null;
    try {
      profile = register(agentDir);
      if (skillBindings != null) {
        skillBindings.replaceBindings(name, validateBindable(initialSkills));
      } else if (initialSkills != null && !initialSkills.isEmpty()) {
        throw new IllegalStateException("Agent Skill 绑定服务未装配");
      }
      return profile;
    } catch (RuntimeException e) {
      if (profile != null) {
        agentScheduler.unregisterProfile(profile);
      }
      profileRegistry.remove(name);
      agentStore.delete(agentDir); // 回滚：把已写的目录删回去
      throw e;
    }
  }

  private Map<String, String> scaffold(
      String name, String description, String provider, String model) {
    String desc = description == null || description.isBlank() ? "描述这个 Agent 做什么" : description;
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "AGENT.md",
        AGENT_MD_TEMPLATE
            .replace("{name}", name)
            .replace("{description}", desc)
            .replace("{provider}", provider)
            .replace("{model}", model));
    files.put("scripts/example.py", SCRIPT_TEMPLATE);
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
    if (skillBindings != null) {
      skillBindings.logCurrentIssues();
    }
    return profile;
  }

  /** WorkspaceWatcher 刷新用（issue #61）：先注销旧定时，再按目录重注册。 */
  public Profile refresh(Path agentDir) {
    // 同名 Profile 已在册时先注销其旧定时：否则重复编辑会让旧 cron 句柄被
    // registerProfile 覆盖而永不 cancel（句柄泄漏 + 同一任务双跑）。
    // 首次出现（无旧 Profile）时 ifPresent 跳过，等价于 register。
    String name = String.valueOf(agentDir.getFileName());
    profileRegistry.get(name).ifPresent(agentScheduler::unregisterProfile);
    return register(agentDir);
  }

  public Optional<Profile> get(String name) {
    return profileRegistry.get(name);
  }

  public Collection<Profile> list() {
    return profileRegistry.all();
  }

  /** 更新：覆写 AGENT.md；先注销旧定时、再注册新的（旧 cron 不会跟新 cron 一起跑）。 */
  public Profile update(String name, String agentMarkdown) {
    return saveFiles(name, Map.of("AGENT.md", agentMarkdown), null);
  }

  /**
   * 编辑基本信息：只改 AGENT.md frontmatter 里的若干 key（description / provider.name / provider.model），正文与未提及的
   * frontmatter 字段原样保留（不丢指令、不丢定时/工具等配置）。 Skill 绑定另由 {@link AgentSkillBindingService} 管理，绝不写回
   * AGENT.md。 传 null 的字段保持原值；description 清空→置空； provider.model 为空则沿用原值（model 为必填，不允许清空）。 先合成新
   * markdown 并用 {@link AgentLoader#parse} 预校验（非法→抛 ProfileValidationException，且不落盘、不破坏原文件），通过再走
   * {@link #update} 重写+重注册。
   */
  public Profile updateBasicInfo(String name, String description, String provider, String model) {
    String raw = agentStore.read(name);
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(raw);
    // frontmatter 来自 snakeyaml 解析，本身是 LinkedHashMap（保序）；复制进可变 Map 再改，避免改动原始不可变 Map
    Map<String, Object> fm = new LinkedHashMap<>(parsed.frontmatter());
    if (description != null) {
      String d = description.strip();
      if (d.isEmpty()) {
        fm.remove("description");
      } else {
        fm.put("description", d);
      }
    }
    if (provider != null && !provider.isBlank()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> pm =
          fm.get("provider") instanceof Map
              ? new LinkedHashMap<>((Map<String, Object>) fm.get("provider"))
              : new LinkedHashMap<>();
      pm.put("name", provider.strip());
      if (model != null && !model.isBlank()) {
        pm.put("model", model.strip());
      }
      fm.put("provider", pm);
    } else if (model != null && !model.isBlank()) {
      // 只改 model、provider 不变：直接在原 provider 段更新 model
      @SuppressWarnings("unchecked")
      Map<String, Object> pm =
          fm.get("provider") instanceof Map
              ? new LinkedHashMap<>((Map<String, Object>) fm.get("provider"))
              : new LinkedHashMap<>();
      pm.put("model", model.strip());
      fm.put("provider", pm);
    }
    String newMarkdown = assembleMarkdown(fm, parsed.body());
    agentLoader.parse(newMarkdown, name); // 预校验：非法定义直接抛，不破坏原文件
    return update(name, newMarkdown);
  }

  /** 把改好的 frontmatter Map + 正文重新拼成 AGENT.md（与 {@link AgentMarkdown#split} 的围栏约定一致）。 */
  private static String assembleMarkdown(Map<String, Object> frontmatter, String body) {
    DumperOptions opts = new DumperOptions();
    opts.setDefaultFlowStyle(FlowStyle.BLOCK);
    String yaml = new Yaml(opts).dump(frontmatter);
    return "---\n" + yaml + "---\n\n" + body + "\n";
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
   * 同上，但允许用户显式指定必须启用的 Skill。作者建议与必选项只存在于返回 sidecar，最终 AGENT.md 不保留旧版顶层 {@code
   * skills}。传空列表则完全由作者模型按需建议。指定了不可绑定 Skill → 400。
   */
  public Map<String, String> generateFiles(
      String name, String description, String notifyChannel, List<String> requiredSkills) {
    return generateDraft(name, description, notifyChannel, requiredSkills).files();
  }

  /**
   * 同上，但允许用户在前端**显式选好 provider/model**：生成时直接把它们写进输出 AGENT.md 的 frontmatter（覆盖默认/沿用逻辑），
   * 让「用大模型生成」也尊重用户在新建页挑的模型。provider/model 为空则沿用原有逻辑（已存在 Agent 用其 provider，否则用作者 provider）。
   */
  public Map<String, String> generateFiles(
      String name,
      String description,
      String notifyChannel,
      List<String> requiredSkills,
      String provider,
      String model) {
    return generateDraft(name, description, notifyChannel, requiredSkills, provider, model).files();
  }

  public GeneratedAgentDraft generateDraft(
      String name, String description, String notifyChannel, List<String> requiredSkills) {
    return generateDraft(name, description, notifyChannel, requiredSkills, null, null);
  }

  /** 生成草稿，并在指定时保留用户明确选择的 provider/model。 */
  public GeneratedAgentDraft generateDraft(
      String name,
      String description,
      String notifyChannel,
      List<String> requiredSkills,
      String provider,
      String model) {
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
    List<SkillCatalogEntry> candidates = availableCatalogCandidates();
    Set<String> candidateNames =
        candidates.stream().map(SkillCatalogEntry::name).collect(Collectors.toSet());
    List<String> required = normalizedSkills(requiredSkills);
    for (String skill : required) {
      if (!candidateNames.contains(skill)) {
        throw new IllegalArgumentException("Skill 不在可访问且已安装的 catalog 中: " + skill);
      }
    }
    // provider/model：用户在前端挑了就用挑的；否则沿用已有 Agent 的 provider，再否则用作者 provider
    String outputProvider =
        (provider != null && !provider.isBlank())
            ? provider
            : profileRegistry.get(name).map(p -> p.provider().name()).orElse(genProvider);
    String modelHint = (model != null && !model.isBlank()) ? model : "该 provider 下合理的模型名";
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
                .replace("{model}", modelHint)
                .replace("{tools}", describeTools())
                .replace("{mcp_servers}", describeMcpServers())
                .replace("{skills}", describeSkills(candidates))
                .replace("{required_skills}", requiredSkillsDirective(required))
                .replace("{notify}", notifyDirective(channel))
                .replace("{example}", AUTHOR_EXAMPLE)
            + description;
    String text =
        providerService.chat("agent-author-" + name, genProfile, ProviderRequest.of(prompt)).text();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("模型未返回内容"); // → 503
    }
    return parseGeneratedDraft(text, name, required, candidateNames);
  }

  private GeneratedAgentDraft parseGeneratedDraft(
      String text, String name, List<String> required, Set<String> candidateNames) {
    // 多文件解析（模型自己决定要不要脚本/子指令）：按 ===FILE: path=== 切分；无分隔符则整段当 AGENT.md
    Map<String, String> files = parseGeneratedFiles(text);
    for (String path : files.keySet()) {
      Path relative = Path.of(path).normalize();
      if (isIllegalGeneratedPath(relative)) {
        throw new IllegalArgumentException("作者模型产出了非法或保留路径: " + path);
      }
    }
    String agentMarkdown = files.get("AGENT.md");
    if (agentMarkdown == null || agentMarkdown.isBlank()) {
      throw new IllegalArgumentException("生成结果缺少 AGENT.md");
    }
    List<String> suggested = AgentMarkdown.legacySkills(agentMarkdown).stream().distinct().toList();
    for (String skill : suggested) {
      if (!candidateNames.contains(skill)) {
        throw new IllegalArgumentException("作者模型建议了列表外或未安装 Skill: " + skill);
      }
    }
    agentMarkdown = AgentMarkdown.removeLegacySkills(agentMarkdown);
    if (AgentMarkdown.hasLegacySkills(agentMarkdown)) {
      throw new IllegalArgumentException("生成结果中的顶层 skills 未能安全移除");
    }
    files.put("AGENT.md", agentMarkdown);
    agentLoader.parse(agentMarkdown, name); // 校验：解析不成合法定义就抛 ProfileValidationException（→400）
    Set<String> bindingSet = new java.util.TreeSet<>(required);
    bindingSet.addAll(suggested);
    return new GeneratedAgentDraft(files, required, suggested, List.copyOf(bindingSet));
  }

  private static boolean isIllegalGeneratedPath(Path relative) {
    if (relative.isAbsolute() || relative.startsWith(PARENT_PATH_SEGMENT)) {
      return true;
    }
    return relative.getNameCount() > 0 && "skills".equals(relative.getName(0).toString());
  }

  /**
   * 保存一组（可能被用户改过的）Agent 文件并即时生效：先校验 AGENT.md 可解析（非法 → 400，不写坏目录）→ 写入 → 覆写后重注册（schedules
   * 变更先注销旧句柄再注册新的，同 {@link #update}）。用于「生成/编辑 Agent」的保存与文件浏览器的多文件保存。
   */
  public Profile saveFiles(String name, Map<String, String> files) {
    return saveFiles(name, files, null);
  }

  public Profile saveFiles(String name, Map<String, String> files, List<String> bindingSkills) {
    String agentMarkdown = files == null ? null : files.get("AGENT.md");
    if (agentMarkdown == null || agentMarkdown.isBlank()) {
      throw new IllegalArgumentException("缺少 AGENT.md 内容");
    }
    rejectLegacySkills(agentMarkdown);
    agentLoader.parse(agentMarkdown, name); // 先校验再落盘：非法定义不写进目录
    List<String> validated = bindingSkills == null ? null : validateBindable(bindingSkills);
    Profile old = profileRegistry.get(name).orElse(null);
    if (validated != null && skillBindings == null) {
      throw new IllegalStateException("Agent Skill 绑定服务未装配");
    }
    List<String> previousBindings =
        validated == null || old == null
            ? List.of()
            : skillBindings.inspect(name).bindings().stream()
                .map(BoundSkillDescriptor::name)
                .toList();
    AgentStore.FileSnapshot snapshot =
        old == null ? null : agentStore.snapshot(name, files.keySet());
    Path agentDir = null;
    Profile updated = null;
    try {
      agentDir = agentStore.writeAll(name, files);
      updated = agentLoader.deriveProfile(agentDir);
      if (validated != null) {
        skillBindings.replaceBindings(name, validated);
      }
      if (old != null) {
        agentScheduler.unregisterProfile(old);
      }
      profileRegistry.register(updated);
      if (!updated.schedules().isEmpty()) {
        agentScheduler.registerProfile(updated);
      }
    } catch (IOException e) {
      rollbackSave(name, agentDir, old, null, snapshot, validated, previousBindings);
      throw new UncheckedIOException("读取 Agent 目录失败: " + name, e);
    } catch (RuntimeException e) {
      rollbackSave(name, agentDir, old, updated, snapshot, validated, previousBindings);
      throw e;
    }
    if (skillBindings != null) {
      skillBindings.logCurrentIssues();
    }
    return updated;
  }

  private void rollbackSave(
      String name,
      Path agentDir,
      Profile old,
      Profile updated,
      AgentStore.FileSnapshot snapshot,
      List<String> validated,
      List<String> previousBindings) {
    if (validated != null && skillBindings != null) {
      try {
        skillBindings.replaceBindings(name, previousBindings);
      } catch (RuntimeException ignored) {
        // 原始异常优先；reconcile 会报告任何回滚残留。
      }
    }
    if (updated != null) {
      try {
        agentScheduler.unregisterProfile(updated);
      } catch (RuntimeException ignored) {
        // 继续恢复文件与旧 Profile。
      }
    }
    if (old == null) {
      profileRegistry.remove(name);
      if (agentDir != null) {
        agentStore.delete(agentDir);
      }
      return;
    }
    if (snapshot != null) {
      agentStore.restore(snapshot);
    }
    profileRegistry.register(old);
    if (!old.schedules().isEmpty()) {
      agentScheduler.registerProfile(old);
    }
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

  /** 把已过滤 catalog（名+描述）铺给作者模型；模型据此生成瞬时建议，禁止编造清单外名字。 */
  private String describeSkills(List<SkillCatalogEntry> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return "（当前无可用 Skill）";
    }
    return candidates.stream()
        .sorted(Comparator.comparing(SkillCatalogEntry::name))
        .map(
            skill ->
                "- "
                    + skill.name()
                    + " ["
                    + skill.visibility()
                    + "/"
                    + skill.source()
                    + "]："
                    + oneLine(skill.description()))
        .collect(Collectors.joining("\n"));
  }

  private List<SkillCatalogEntry> availableCatalogCandidates() {
    if (skillCatalog == null || skillRegistry == null) {
      return List.of();
    }
    List<SkillCatalogEntry> entries = skillCatalog.query("", null);
    Map<String, SkillCatalogEntry> unique = new LinkedHashMap<>();
    for (SkillCatalogEntry entry : entries) {
      if (unique.putIfAbsent(entry.name(), entry) != null) {
        throw new IllegalStateException("Skill catalog 存在同名公共/私有冲突: " + entry.name());
      }
    }
    return unique.values().stream()
        .filter(entry -> entry.installed() && skillRegistry.exists(entry.name()))
        .sorted(Comparator.comparing(SkillCatalogEntry::name))
        .toList();
  }

  private List<String> validateBindable(List<String> names) {
    List<String> normalized = normalizedSkills(names);
    Set<String> available =
        availableCatalogCandidates().stream()
            .map(SkillCatalogEntry::name)
            .collect(Collectors.toSet());
    for (String name : normalized) {
      if (!available.contains(name)) {
        throw new IllegalArgumentException("Skill 不在可访问且已安装的 catalog 中: " + name);
      }
    }
    return normalized;
  }

  private static List<String> normalizedSkills(List<String> names) {
    if (names == null) {
      return List.of();
    }
    return names.stream()
        .map(String::strip)
        .filter(name -> !name.isEmpty())
        .distinct()
        .sorted()
        .toList();
  }

  private static void rejectLegacySkills(String markdown) {
    if (AgentMarkdown.hasLegacySkills(markdown)) {
      throw new IllegalArgumentException("运行期禁止写入旧版顶层 skills；请使用 Agent Skill 绑定 API");
    }
  }

  /** 用户显式指定必启用的 Skill：作为 sidecar 硬约束；没指定则为空。目标由人定，不让模型漏。 */
  private static String requiredSkillsDirective(List<String> required) {
    if (required == null || required.isEmpty()) {
      return "";
    }
    return "【用户已指定：生成建议必须包含这些 Skill；后端会把它们写入 sidecar，不写入最终 AGENT.md】" + String.join("、", required);
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
    if (skillBindings != null) {
      skillBindings.logCurrentIssues();
    }
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
