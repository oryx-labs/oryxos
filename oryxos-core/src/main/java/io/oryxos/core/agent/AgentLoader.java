package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.ProfileValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个目录 = 一个 Agent（第 29 节）：扫描 {@code .oryxos/agents/}，把每个子目录的 {@code AGENT.md} 派生成一个底座认识的 {@link
 * Profile} 并登记——从而零改动复用整台底座。
 *
 * <p>派生复用 {@link ProfileLoader#fromMap} 那套校验（同一异常同一消息，FR-006）。 坏 Agent 记 ERROR
 * 跳过、不阻断其余加载与启动（FR-009）； {@code tools} 引用未注册能力时 WARN 但仍登记。 provider 名单/工具名单由装配方注入——core 不反向依赖
 * provider/tool 模块。
 */
public class AgentLoader {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLoader.class);
  private static final String AGENT_FILE = "AGENT.md";

  private final Path agentsDir;
  private final Set<String> knownTools;
  private final ProfileLoader validator;

  public AgentLoader(Path agentsDir, Set<String> knownProviders) {
    this(agentsDir, knownProviders, Set.of());
  }

  public AgentLoader(Path agentsDir, Set<String> knownProviders, Set<String> knownTools) {
    this.agentsDir = agentsDir;
    this.knownTools = Set.copyOf(knownTools);
    // profilesDir 参数对 fromMap 无用，仅为复用 ProfileLoader 的校验与 env 解析
    this.validator = new ProfileLoader(agentsDir, knownProviders);
  }

  /** 扫描目录区并返回加载成功的 Agent 索引；单目录失败只记日志、不阻断（FR-002 扫 N 得 N、不产生别的）。 */
  public ProfileRegistry loadAll() {
    ProfileRegistry registry = new ProfileRegistry();
    if (!Files.isDirectory(agentsDir)) {
      LOG.warn("Agent 目录不存在，跳过加载: {}", sanitize(agentsDir.toString()));
      return registry;
    }
    try (Stream<Path> dirs = Files.list(agentsDir)) {
      dirs.filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir -> {
                try {
                  registry.register(deriveProfile(dir));
                } catch (RuntimeException | IOException e) {
                  LOG.error(
                      "跳过损坏的 Agent 目录 {}: {}",
                      sanitize(String.valueOf(dir.getFileName())),
                      sanitize(e.getMessage()));
                }
              });
    } catch (IOException e) {
      LOG.error("扫描 Agent 目录失败: {}", sanitize(e.getMessage()));
    }
    return registry;
  }

  /**
   * 单个目录派生：拆 {@code AGENT.md} frontmatter/正文 → 复用 {@code ProfileLoader.fromMap} 校验 → {@link
   * Profile}。缺 name/provider 抛 {@link ProfileValidationException}（与启动同一异常同一消息）。
   */
  Profile deriveProfile(Path agentDir) throws IOException {
    Path agentMd = agentDir.resolve(AGENT_FILE);
    if (!Files.isRegularFile(agentMd)) {
      throw new ProfileValidationException("Agent 目录缺少 AGENT.md: " + agentDir.getFileName());
    }
    return parse(Files.readString(agentMd), String.valueOf(agentDir.getFileName()));
  }

  /**
   * 从一段 {@code AGENT.md} 文本派生 + 校验 {@link Profile}（不落盘）——供 30 节"一句话生成"校验草稿是否能解析成合法定义。 缺
   * name/provider 抛 {@link ProfileValidationException}（与目录派生同一套校验）。
   */
  Profile parse(String agentMarkdown, String source) {
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(agentMarkdown);
    Profile profile = validator.fromMap(parsed.frontmatter(), source);
    warnUnknownTools(profile, source);
    return profile;
  }

  private void warnUnknownTools(Profile profile, String source) {
    if (knownTools.isEmpty()) {
      return;
    }
    for (String tool : profile.tools()) {
      if (!knownTools.contains(tool)) {
        LOG.warn("Agent {} 引用了未注册的能力: {}", sanitize(source), sanitize(tool));
      }
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
