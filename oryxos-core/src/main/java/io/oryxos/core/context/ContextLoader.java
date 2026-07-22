package io.oryxos.core.context;

import io.oryxos.core.agent.AgentMarkdown;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.skill.SkillMetadata;
import io.oryxos.core.skill.SkillSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * system prompt 上下文供给者：把 identity.prompt、这个 Agent 自己 {@code AGENT.md} 的正文、 与 Profile 的 bootstrap
 * 文件按序拼接（宪法 IV：一个 Agent 目录是 prompt 输入，不是可执行 Tool）。
 *
 * <p>一个目录 = 一个 Agent（第 29 节）：正文现读自 {@code .oryxos/agents/<name>/AGENT.md}，去掉 frontmatter 后注入。
 * 两条铁律（TechSol §8.3）：每次调用重新读文件、无任何缓存（用户改完正文下一次触发立即生效）； Bootstrap 缺失 WARN——静默跳过会造成"人格悄悄丢了"这类最难查的软故障。
 */
public class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  private static final String AGENTS_DIR = "agents";
  private static final String AGENT_FILE = "AGENT.md";
  private static final String READ_FILE_TOOL = "read_file";

  private final Path oryxosRoot;

  public ContextLoader(Path oryxosRoot) {
    this.oryxosRoot = oryxosRoot;
  }

  public String load(Profile profile) {
    return load(profile, SkillSnapshot.empty(profile.name()));
  }

  /**
   * 组装一次 Prompt 的 system context。Skill 只能来自顶层请求已经冻结的快照；本层只渲染 L1， 不扫描目录，也不读取 {@code SKILL.md}
   * 正文或任何资源。
   */
  public String load(Profile profile, SkillSnapshot skills) {
    if (!profile.name().equals(skills.agentName())) {
      throw new IllegalArgumentException("Skill 快照不属于当前 Agent: " + sanitize(profile.name()));
    }
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
    for (String bootstrap : profile.bootstrap()) {
      Path file = oryxosRoot.resolve(bootstrap);
      if (!Files.isRegularFile(file)) {
        LOG.warn("Bootstrap 文件缺失，跳过: {}", sanitize(bootstrap));
        continue;
      }
      context.append(read(file)).append('\n');
    }
    appendSkillCatalog(context, profile, skills);
    return context.toString();
  }

  private static void appendSkillCatalog(
      StringBuilder context, Profile profile, SkillSnapshot snapshot) {
    if (snapshot.skills().isEmpty()) {
      return;
    }
    context
        .append("## Available Skills\n")
        .append("Only metadata is loaded. When relevant, call read_file with the entry path.\n\n");
    for (SkillMetadata skill : snapshot.skills()) {
      context
          .append("- name: ")
          .append(sanitizeLine(skill.name()))
          .append('\n')
          .append("  description: ")
          .append(sanitizeLine(skill.description()))
          .append('\n')
          .append("  entry: ")
          .append(sanitizeLine(skill.entryPath().toString()))
          .append('\n');
    }
    if (!profile.tools().contains(READ_FILE_TOOL)) {
      context.append(
          "Note: this Agent cannot load Skill entry content because read_file is not enabled.\n");
      LOG.warn("Agent {} 有可用 Skill，但 Profile 未声明 read_file；仅展示目录且不自动扩权", sanitize(profile.name()));
    }
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

  /** Prompt 行消毒：所有 Unicode 控制字符替换为空格，防伪造新条目或 prompt 结构。 */
  private static String sanitizeLine(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder sanitized = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(
            codePoint -> {
              int type = Character.getType(codePoint);
              if (Character.isISOControl(codePoint)
                  || type == Character.FORMAT
                  || type == Character.LINE_SEPARATOR
                  || type == Character.PARAGRAPH_SEPARATOR) {
                sanitized.append(' ');
              } else {
                sanitized.appendCodePoint(codePoint);
              }
            });
    return sanitized.toString();
  }
}
