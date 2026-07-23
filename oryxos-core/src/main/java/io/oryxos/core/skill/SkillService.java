package io.oryxos.core.skill;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全局 Skill 库的生命周期编排（第 32 节）：CRUD + 内置 Skill 播种。
 *
 * <p>与 {@code AgentLifecycleService} 同构但更薄：Skill 是纯共享资产（无 provider/调度/沙箱）。写盘走 {@link
 * SkillStore}、内存索引走 {@link SkillRegistry}，两者在每次增删改后同步（{@code ContextLoader} 按名解析时即时可见）。校验与 web
 * 层解耦：{@link #get} 返回 {@link Optional}，404 由 web 决定；非法入参抛 {@link IllegalArgumentException}（web 映射
 * 400）。
 */
public class SkillService {

  /** 内置 Skill 名（供测试与外部引用）。 */
  static final String BUILTIN_REPORT_FORMAT = "report-format";

  /** 一个内置 Skill 的定义（名 / 描述 / 正文）。 */
  private record Builtin(String name, String description, String body) {}

  /** 内置的一批常用 Skill：都用来约束 Agent 产出。库里没有对应名才播种（幂等，用户改过/删过不覆盖）。 */
  private static final List<Builtin> BUILTINS =
      List.of(
          new Builtin(
              BUILTIN_REPORT_FORMAT,
              "研报 / 日报格式规范：结构化、带出处、按重要性排序",
              """
              # 研报 / 日报格式规范

              产出一份结构化报告，严格遵守：
              - 开头一句今日总览（不超过两句）。
              - 正文按重要性排序，挑最重要的 5~8 条，每条一行：**标题** + 一句话点评 + 来源链接。
              - 命中用户关注方向的条目排在最前。
              - 事实与推断分开写；引用的数据必须带来源，不得编造。
              - 结尾不加口号、不加宣传语。
              - 全文中文点评；专有名词与原文标题可保留原文。"""),
          new Builtin(
              "web-research",
              "网络调研规范：每个结论带出处、区分事实与推断、交叉验证、不编造",
              """
              # 网络调研规范

              做联网调研时严格遵守：
              - 每个关键结论后附来源链接；无可靠来源的判断标注"（推断）"。
              - 事实与推断分开陈述，绝不把推断写成事实。
              - 重要数字/结论尽量用两个独立来源交叉验证，冲突时并列呈现并说明分歧。
              - 引用发布时间，优先近期来源；过期信息要标注时间。
              - 找不到就直说"未找到可靠来源"，绝不编造链接、数字或引文。"""),
          new Builtin(
              "summarize",
              "摘要规范：抓重点、去冗余、限字数、保留关键数字与结论",
              """
              # 摘要规范

              压缩长文 / 多条信息为摘要时：
              - 先给一句话核心结论，再列 3~7 个要点。
              - 保留关键数字、名称、时间、结论；去掉铺垫、重复、套话。
              - 不引入原文没有的信息，不做未经支持的推断。
              - 控制在原文 1/5 以内（另有要求以要求为准）。
              - 用与原文一致的语言输出。"""),
          new Builtin(
              "json-output",
              "结构化 JSON 输出：严格按字段、无多余文本、无代码围栏",
              """
              # 结构化 JSON 输出规范

              当需要机器可解析的结果时：
              - 只输出一个合法 JSON 对象/数组，**不要**任何前后说明文字。
              - **不要**用 Markdown 代码围栏（```）包裹。
              - 严格使用约定的字段名与类型；无值用 null，不要省略字段。
              - 字符串内正确转义；不要输出注释、尾逗号。
              - 无法给出某字段时用 null 并在约定的 error 字段说明，不要编造。"""),
          new Builtin(
              "code-review",
              "代码审查清单：正确性 / 边界 / 安全 / 可读性，给可执行修改",
              """
              # 代码审查清单

              审查代码时逐项过一遍并给出可执行的修改建议：
              - 正确性：逻辑是否符合意图，有无 off-by-one、空指针、并发/竞态问题。
              - 边界与错误处理：异常路径、空集合、超大/超小输入、失败重试。
              - 安全：注入、越权、敏感信息硬编码、不可信输入未校验。
              - 可读性与复用：命名、重复代码、过长函数、可简化处。
              - 每条问题给出：位置 + 为什么是问题 + 建议改法；按严重度排序，先说阻断级。
              - 没问题就明说"未发现明显问题"，不要凑数。"""),
          new Builtin(
              "notify-message",
              "通知 / 群消息文案：要点先行、简洁可执行、无套话",
              """
              # 通知 / 群消息文案规范

              写要推送到群/渠道的消息时：
              - 第一行是结论或最重要的事（让人一眼看懂要不要点开）。
              - 要点用短句或列表，避免大段文字。
              - 需要动作时明确"谁、做什么、到什么时候"。
              - 附关键链接/数据；不加寒暄、口号、表情堆砌。
              - 控制长度，适配 IM 阅读；重要信息不要被淹没在末尾。"""));

  private final SkillStore store;
  private final SkillRegistry registry;
  private final SkillLoader loader;

  public SkillService(SkillStore store, SkillRegistry registry, SkillLoader loader) {
    this.store = store;
    this.registry = registry;
    this.loader = loader;
  }

  /** 新建 Skill：name 冲突第一步就拒；写盘 → 解析 → 登记内存索引。 */
  public Skill create(String name, String description, String body) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Skill name 为空");
    }
    if (registry.exists(name) || store.exists(name)) {
      throw new IllegalArgumentException("Skill 已存在: " + name);
    }
    return writeAndRegister(name, description, body);
  }

  /** 更新 Skill：必须已存在；覆写正文与描述并即时生效。 */
  public Skill update(String name, String description, String body) {
    if (!registry.exists(name) && !store.exists(name)) {
      throw new IllegalArgumentException("Skill 不存在: " + name);
    }
    return writeAndRegister(name, description, body);
  }

  /** 删除 Skill：物理删目录 + 移出内存索引。 */
  public void delete(String name) {
    store.delete(name);
    registry.remove(name);
  }

  public Optional<Skill> get(String name) {
    return registry.get(name);
  }

  public Collection<Skill> list() {
    return registry.all();
  }

  /** 播种内置的一批 Skill：逐个"库里没有才写"（幂等，用户改过/删过的名不重复播种）。启动时调一次。 */
  public void seedBuiltins() {
    for (Builtin b : BUILTINS) {
      if (!store.exists(b.name()) && !registry.exists(b.name())) {
        writeAndRegister(b.name(), b.description(), b.body());
      }
    }
  }

  /**
   * 从一段 SKILL.md 文本导入 Skill（配合"给 URL 导入"：HTTP 拉取在 web 层完成，这里只认文本，保持 core 无 HTTP 依赖）。name 取 {@code
   * nameOverride}（非空优先）→ frontmatter 的 name → {@code fallbackName}（如 URL 推断名）。同名 → 400。
   */
  public Skill importMarkdown(String nameOverride, String markdown, String fallbackName) {
    if (markdown == null || markdown.isBlank()) {
      throw new IllegalArgumentException("导入内容为空");
    }
    Skill parsed = loader.parse(markdown, fallbackName);
    String name =
        nameOverride != null && !nameOverride.isBlank() ? nameOverride.strip() : parsed.name();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("无法确定 Skill 名（SKILL.md 无 name 字段，也未指定 name）");
    }
    if (registry.exists(name) || store.exists(name)) {
      throw new IllegalArgumentException("Skill 已存在: " + name);
    }
    return writeAndRegister(name, parsed.description(), parsed.body());
  }

  /** SKILL.md 在 {@link #importFiles} 的文件 Map 里的键——目录根下必须恰好叫这个名字。 */
  private static final String SKILL_FILE = "SKILL.md";

  /**
   * 从一整份目录导入 Skill（配合"从 GitHub 目录导入"：拉取在 web 层完成，这里只认"相对路径 → 内容"的 Map，保持 core 无 HTTP 依赖）。{@code
   * files} 必须含根下的 {@code SKILL.md}；其余文件（脚本、参考资料等）原样落盘，不经过 {@link #writeAndRegister} 的单文件重组——保留原始
   * frontmatter/正文格式。name 取 {@code nameOverride}（非空优先）→ frontmatter 的 name → {@code
   * fallbackName}。同名 → 400。
   */
  public Skill importFiles(String nameOverride, Map<String, String> files, String fallbackName) {
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("导入内容为空");
    }
    String skillMd = files.get(SKILL_FILE);
    if (skillMd == null || skillMd.isBlank()) {
      throw new IllegalArgumentException("目录缺少 " + SKILL_FILE + "，无法导入");
    }
    Skill parsed = loader.parse(skillMd, fallbackName);
    String name =
        nameOverride != null && !nameOverride.isBlank() ? nameOverride.strip() : parsed.name();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("无法确定 Skill 名（SKILL.md 无 name 字段，也未指定 name）");
    }
    if (registry.exists(name) || store.exists(name)) {
      throw new IllegalArgumentException("Skill 已存在: " + name);
    }
    store.writeAll(name, files); // name 非法在此抛（SkillStore.safe）
    // 不能再用 loader.parse(skillMd, name) 重新派生——SKILL.md 里若有 name 字段，parse 会优先用它，
    // 那样 nameOverride 就白传了；这里直接拿 name（已按 override 优先解析过）+ 已解析出的 description/body。
    Skill skill = new Skill(name, parsed.description(), parsed.body());
    registry.register(skill);
    return skill;
  }

  private Skill writeAndRegister(String name, String description, String body) {
    String markdown = toSkillMarkdown(name, description, body);
    store.write(name, markdown); // name 非法在此抛（SkillStore.safe）
    Skill skill = loader.parse(markdown, name);
    registry.register(skill);
    return skill;
  }

  /**
   * 组装 SKILL.md 文本：frontmatter(name/description) + 正文。描述空则省略该行（避免 YAML 空值 null 破坏 frontmatter 解析）；
   * 非空则用双引号标量并转义，容忍描述里含冒号/引号。
   */
  private static String toSkillMarkdown(String name, String description, String body) {
    String text = body == null ? "" : body;
    StringBuilder frontmatter = new StringBuilder("---\nname: ").append(name).append('\n');
    if (description != null && !description.isBlank()) {
      frontmatter.append("description: ").append(yamlQuote(description)).append('\n');
    }
    frontmatter.append("---\n\n").append(text).append('\n');
    return frontmatter.toString();
  }

  /** 最小 YAML 双引号标量转义：反斜杠与双引号，够覆盖单行描述。 */
  private static String yamlQuote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
