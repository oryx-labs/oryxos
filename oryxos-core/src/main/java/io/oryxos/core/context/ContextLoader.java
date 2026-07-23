package io.oryxos.core.context;

import io.oryxos.core.agent.AgentMarkdown;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.skill.Skill;
import io.oryxos.core.skill.SkillRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * system prompt 上下文供给者：把 identity.prompt、这个 Agent 自己 {@code AGENT.md} 的正文、 Agent 引用的全局 Skill 正文、与
 * Profile 的 bootstrap 文件按序拼接（宪法 IV 修订：Agent 目录是 prompt 输入；Skill 是 Agent 按名引用的全局能力库）。
 *
 * <p>一个目录 = 一个 Agent（第 29 节）：正文现读自 {@code .oryxos/agents/<name>/AGENT.md}，去掉 frontmatter 后注入。
 * 两条铁律（TechSol §8.3）：每次调用重新读文件、无任何缓存（用户改完正文下一次触发立即生效）； Bootstrap 缺失 WARN——静默跳过会造成"人格悄悄丢了"这类最难查的软故障。
 *
 * <p>Skill 注入（第 32 节）：{@code AGENT.md} frontmatter 声明的 {@code skills:[名]} 按名从全局 {@link
 * SkillRegistry} 解析，把 Skill 正文注入 system prompt——这样 Skill 才能"强约束"Agent 产出（而非靠模型自觉 read_file）。引用了不存在的
 * Skill 记 WARN 跳过，同 Bootstrap 缺失的处理。
 */
public class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  private static final String AGENTS_DIR = "agents";
  private static final String AGENT_FILE = "AGENT.md";
  private static final String OUTPUT_DIR = "output";

  /** 具备写盘能力的工具：任一在场就把该 Agent 的绝对产出目录告诉它（否则不加，省 prompt）。 */
  private static final Set<String> FILE_WRITE_TOOLS =
      Set.of("write_file", "append_file", "edit_file", "make_dir", "download_file");

  private final Path oryxosRoot;
  private final SkillRegistry skillRegistry;

  public ContextLoader(Path oryxosRoot, SkillRegistry skillRegistry) {
    this.oryxosRoot = oryxosRoot;
    this.skillRegistry = skillRegistry;
  }

  public String load(Profile profile) {
    StringBuilder context = new StringBuilder();
    if (profile.identity() != null && profile.identity().prompt() != null) {
      context.append(profile.identity().prompt()).append('\n');
    }
    // AGENT.md 正文：现读、无缓存——改正文后下一次触发即生效（渐进式披露：正文常驻，子资源按需）
    Path agentMd = oryxosRoot.resolve(AGENTS_DIR).resolve(profile.name()).resolve(AGENT_FILE);
    if (Files.isRegularFile(agentMd)) {
      String body = AgentMarkdown.split(read(agentMd)).body();
      if (!body.isBlank()) {
        context.append(body).append('\n');
      }
    }
    // 引用的全局 Skill：按名解析并注入正文（约束产出）；库里没有的记 WARN 跳过，不阻断
    appendSkills(context, profile);
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

  /** 把 Profile 引用的全局 Skill 正文按序注入；skillRegistry 未装配或引用不存在均安全跳过。 */
  private void appendSkills(StringBuilder context, Profile profile) {
    if (skillRegistry == null) {
      return;
    }
    for (String name : profile.skills()) {
      Skill skill = skillRegistry.get(name).orElse(null);
      if (skill == null) {
        LOG.warn("Agent 引用了不存在的 Skill，跳过: {}", sanitize(name));
        continue;
      }
      if (!skill.body().isBlank()) {
        context.append(skill.body()).append('\n');
      }
    }
  }

  /** 会写盘的 Agent：注入共享产出目录（{@code .oryxos/output/} 绝对路径，已在白名单内、管理台「输出」tab 直接可见）。 */
  private void appendOutputDir(StringBuilder context, Profile profile) {
    boolean canWrite = profile.tools().stream().anyMatch(FILE_WRITE_TOOLS::contains);
    if (!canWrite) {
      return;
    }
    Path outputDir = oryxosRoot.resolve(OUTPUT_DIR).toAbsolutePath().normalize();
    context
        .append("你的文件产出目录（绝对路径，必须严格使用）：")
        .append(outputDir)
        .append("。需要落盘的报告 / 汇总 / 导出等，一律用 write_file 写到这个目录下，文件名带上你的名字与日期，如 ")
        .append(profile.name())
        .append("_report_2026-07-23.md。不要写到 output/、./output 等相对路径（会被沙箱拒绝，且管理台看不到）。\n");
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      // 文件存在但读不出来（权限/编码）不属于"缺失可跳过"，必须显式失败
      throw new IllegalStateException("读取上下文文件失败: " + file.getFileName(), e);
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
