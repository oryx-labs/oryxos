package io.oryxos.core.context;

import io.oryxos.core.agent.AgentMarkdown;
import io.oryxos.core.capability.CapabilityAssembly;
import io.oryxos.core.capability.CapabilityAssemblyContext;
import io.oryxos.core.knowledge.BoundKnowledgeDescriptor;
import io.oryxos.core.knowledge.KnowledgeBindingInspection;
import io.oryxos.core.knowledge.KnowledgeBindingIssue;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.skill.AgentSkillBindingReader;
import io.oryxos.core.skill.BindingInspection;
import io.oryxos.core.skill.BoundSkillDescriptor;
import io.oryxos.core.skill.SkillBindingIssue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * system prompt 上下文供给者：把 identity.prompt、这个 Agent 自己 {@code AGENT.md} 的正文、当前 Agent 绑定的 Skill 元数据目录与
 * Profile 的 bootstrap 文件按序拼接。
 *
 * <p>一个目录 = 一个 Agent（第 29 节）：正文现读自 {@code .oryxos/agents/<name>/AGENT.md}，去掉 frontmatter 后注入。
 * 两条铁律（TechSol §8.3）：每次调用重新读文件、无任何缓存（用户改完正文下一次触发立即生效）； Bootstrap 缺失 WARN——静默跳过会造成"人格悄悄丢了"这类最难查的软故障。
 *
 * <p>Skill 渐进披露：每次只扫描 {@code agents/<name>/skills/} 的有效相对软连接，注入 name、description 和 Agent 本地
 * SKILL.md 绝对路径；正文/脚本/参考绝不预载，由模型用既有 read_file/shell 按需加载并进入工具审计。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "skillRegistry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
public class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  private static final String AGENTS_DIR = "agents";
  private static final String AGENT_FILE = "AGENT.md";
  private static final String OUTPUT_DIR = "output";

  /** AGENT.md / bootstrap 单文件上限（与 FileTools / workspace /file 的 10 MiB 对齐）。 */
  static final long MAX_CONTEXT_FILE_BYTES = 10L * 1024 * 1024;

  /** 具备写盘能力的工具：任一在场就把该 Agent 的绝对产出目录告诉它（否则不加，省 prompt）。 */
  private static final Set<String> FILE_WRITE_TOOLS =
      Set.of("write_file", "append_file", "edit_file", "make_dir", "download_file", "shell");

  /** 检索工具名：Profile 声明了它才注入知识库元数据（对照 FILE_WRITE_TOOLS 的按需注入模式）。 */
  private static final String RETRIEVE_KNOWLEDGE_TOOL = "retrieve_knowledge";

  private final Path oryxosRoot;
  private final AgentSkillBindingReader skillBindings;
  private final KnowledgeBindingService knowledgeBindings;

  public ContextLoader(Path oryxosRoot, AgentSkillBindingReader skillBindings) {
    this(oryxosRoot, skillBindings, null);
  }

  public ContextLoader(
      Path oryxosRoot,
      AgentSkillBindingReader skillBindings,
      KnowledgeBindingService knowledgeBindings) {
    this.oryxosRoot = oryxosRoot;
    this.skillBindings = skillBindings;
    this.knowledgeBindings = knowledgeBindings;
  }

  public String load(Profile profile) {
    StringBuilder context = new StringBuilder();
    if (profile.identity() != null && profile.identity().prompt() != null) {
      context.append(profile.identity().prompt()).append('\n');
    }
    // 025：结构化人格段——固定模板渲染，插在正文之前（身份先于任务）；无 persona 不注入
    if (profile.persona() != null) {
      context.append(renderPersona(profile.persona())).append('\n');
    }
    // AGENT.md 正文：现读、无缓存——改正文后下一次触发即生效（渐进式披露：正文常驻，子资源按需）
    Path agentMd = oryxosRoot.resolve(AGENTS_DIR).resolve(profile.name()).resolve(AGENT_FILE);
    if (Files.isRegularFile(agentMd)) {
      String body = AgentMarkdown.split(read(agentMd)).body();
      if (!body.isBlank()) {
        context.append(body).append('\n');
      }
    }
    // 当前 Agent 的有效 Skill：只注入目录元数据与读取路径，正文由 read_file 按需加载
    appendSkills(context, profile);
    // 当前 Agent 绑定的知识库：只注入 name + description + 检索指引；零绑定零注入、正文永不预载（FR-005）
    appendKnowledge(context, profile);
    // 告知会写盘的 Agent 它的绝对产出目录（已在文件白名单内），落盘文件有确定去处，避免它猜 ./output 撞沙箱
    appendOutputDir(context, profile);
    for (String bootstrap : profile.bootstrap()) {
      Path file = oryxosRoot.resolve(bootstrap);
      if (!Files.isRegularFile(file)) {
        LOG.warn("Bootstrap 文件缺失，跳过: {}", sanitize(bootstrap));
        continue;
      }
      context.append(read(file)).append('\n');
    }
    return context.toString();
  }

  /** 每轮重扫 Agent 本地绑定；问题项记录 WARN 并跳过，合法项只注入元数据。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "Every untrusted issue string is passed through sanitize before logging.")
  private void appendSkills(StringBuilder context, Profile profile) {
    if (skillBindings == null) {
      return;
    }
    BindingInspection inspection = skillBindings.inspect(profile.name());
    for (SkillBindingIssue issue : inspection.issues()) {
      LOG.warn(
          "Agent Skill 绑定异常 [{}]，跳过 {}/{}: {}",
          issue.type(),
          sanitize(issue.agentName()),
          sanitize(issue.entryName()),
          sanitize(issue.message()));
    }
    CapabilityAssembly assembly = CapabilityAssemblyContext.current();
    boolean any = false;
    for (BoundSkillDescriptor binding : inspection.bindings()) {
      if (!assembly.allowsSkill(binding.name())) {
        continue;
      }
      if (!any) {
        context.append(
            "你可以按需使用以下 Skill。仅在当前任务需要时，用 read_file 读取给出的 SKILL.md；"
                + "其中的相对资源路径以该 SKILL.md 所在目录为基准并转换成绝对路径，不要猜测未读取的内容：\n");
        any = true;
      }
      context
          .append("- ")
          .append(binding.name())
          .append("：")
          .append(binding.description())
          .append("\n  SKILL.md：")
          .append(binding.skillFile())
          .append('\n');
    }
  }

  /** 每轮重扫知识库绑定（渐进披露，FR-005）：问题项 WARN 跳过，合法项只注入元数据与检索指引。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "Every untrusted issue string is passed through sanitize before logging.")
  private void appendKnowledge(StringBuilder context, Profile profile) {
    if (knowledgeBindings == null || !profile.tools().contains(RETRIEVE_KNOWLEDGE_TOOL)) {
      return;
    }
    KnowledgeBindingInspection inspection = knowledgeBindings.inspect(profile.name());
    for (KnowledgeBindingIssue issue : inspection.issues()) {
      LOG.warn(
          "Agent 知识库绑定异常 [{}]，跳过 {}/{}: {}",
          issue.type(),
          sanitize(issue.agentName()),
          sanitize(issue.entryName()),
          sanitize(issue.message()));
    }
    CapabilityAssembly assembly = CapabilityAssemblyContext.current();
    boolean any = false;
    for (BoundKnowledgeDescriptor binding : inspection.bindings()) {
      if (!assembly.allowsKnowledge(binding.name())) {
        continue;
      }
      if (!any) {
        context.append(
            "你绑定了以下知识库。回答涉及其中内容时，先用 retrieve_knowledge 检索（结果带出处，"
                + "回答时给出出处）；命中的片段只是入口，不足以回答时按结果里的 file 路径用 read_file 读取原文补充：\n");
        any = true;
      }
      context
          .append("- ")
          .append(binding.name())
          .append("：")
          .append(binding.description())
          .append('\n');
    }
  }

  /** 会写盘的 Agent：注入共享产出目录（{@code .oryxos/output/} 绝对路径，已在白名单内、管理台「输出」tab 直接可见）。 */
  private void appendOutputDir(StringBuilder context, Profile profile) {
    boolean canWrite = profile.tools().stream().anyMatch(FILE_WRITE_TOOLS::contains);
    if (!canWrite) {
      return;
    }
    String relative =
        io.oryxos.core.agent.RunOutputContext.current()
            .map(io.oryxos.core.agent.RunOutputContext::relativeDirectory)
            .orElse(OUTPUT_DIR + "/" + profile.name());
    Path outputDir = oryxosRoot.resolve(relative).toAbsolutePath().normalize();
    context
        .append("你的文件产出目录（文件工具使用绝对路径）：")
        .append(outputDir)
        .append(
            "。调用 write_file / download_file 等文件工具时写到此目录。shell 在本轮独立暂存目录执行，脚本使用相对路径生成产物，成功后发布到此目录的 shell 子目录，返回值给出最终路径。文件名带上你的名字与日期，如 ")
        .append(profile.name())
        .append("_report_2026-07-23.md。文件工具不要使用 output/、./output 等相对路径。\n");
  }

  private static String read(Path file) {
    try {
      long size = Files.size(file);
      if (size > MAX_CONTEXT_FILE_BYTES) {
        throw new IllegalStateException(
            "上下文文件过大（"
                + size
                + " bytes），上限 "
                + MAX_CONTEXT_FILE_BYTES
                + " bytes（10 MiB）: "
                + file.getFileName());
      }
      return Files.readString(file);
    } catch (IOException e) {
      // 文件存在但读不出来（权限/编码）不属于"缺失可跳过"，必须显式失败
      throw new IllegalStateException("读取上下文文件失败: " + file.getFileName(), e);
    }
  }

  /** 人格段固定模板（025）：契约「格式恒定」——字段名与顺序是模板的一部分，不是自由文本。 */
  private static final String PERSONA_HEADING = "## 你的人格（每轮固定，不可违背）\n";

  /** 人格多行值内的行分隔符：tag 与值之间、字段之间都用它拆行（P3C 禁裸魔法值，作常量复用）。 */
  private static final String LINE_BREAK = "\n";

  private static String renderPersona(Profile.Persona p) {
    StringBuilder sb = new StringBuilder(PERSONA_HEADING);
    sb.append("- 你是「").append(p.name()).append("」，角色：").append(p.role()).append(LINE_BREAK);
    appendPersonaField(sb, "性格", p.traits());
    appendPersonaField(sb, "语气", p.tone());
    appendPersonaField(sb, "行为准则", p.values());
    appendPersonaField(sb, "边界", p.boundaries());
    appendPersonaField(sb, "风格示范", p.sampleStyle());
    return sb.toString();
  }

  /**
   * 追加一个「- 标签：值」字段。导入器把 values/tone 写成多行 block scalar（一条规则一行、可含 {@code ###} 分组小标题），直接拼会把续行顶到第 0
   * 列、标签被首行内容吞掉（契约「格式恒定」被破坏）。这里单行值保持一行一字段；多行值标签独立成行、续行统一缩进两个空格成为列表项的延续块。
   */
  private static void appendPersonaField(StringBuilder sb, String label, String value) {
    if (value == null) {
      return;
    }
    sb.append("- ").append(label).append("：");
    if (!value.contains(LINE_BREAK)) {
      sb.append(value).append(LINE_BREAK);
      return;
    }
    sb.append(LINE_BREAK);
    for (String line : value.split(LINE_BREAK, -1)) {
      String t = line.strip();
      if (!t.isEmpty()) {
        sb.append("  ").append(t).append(LINE_BREAK);
      }
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
