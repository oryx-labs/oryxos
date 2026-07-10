package io.oryxos.core.context;

import io.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * system prompt 上下文供给者：按 Profile 的 bootstrap / skills 声明读 {@code .oryxos/} 下文件， 与 identity.prompt
 * 按序拼接（宪法 IV：SKILL.md 是 prompt 输入，不是可执行 Tool）。
 *
 * <p>两条铁律（TechSol §8.3）：每次调用重新读文件、无任何缓存（用户改完立即生效）； Skill 引用缺失抛错点名、Bootstrap 缺失
 * WARN——静默跳过会造成"人格悄悄丢了"这类最难查的软故障。
 */
public class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  private final Path oryxosRoot;

  public ContextLoader(Path oryxosRoot) {
    this.oryxosRoot = oryxosRoot;
  }

  public String load(Profile profile) {
    StringBuilder context = new StringBuilder();
    if (profile.identity() != null && profile.identity().prompt() != null) {
      context.append(profile.identity().prompt()).append('\n');
    }
    for (String bootstrap : profile.bootstrap()) {
      Path file = oryxosRoot.resolve(bootstrap);
      if (!Files.isRegularFile(file)) {
        LOG.warn("Bootstrap 文件缺失，跳过: {}", sanitize(bootstrap));
        continue;
      }
      context.append(read(file)).append('\n');
    }
    for (String skill : profile.skills()) {
      Path file = oryxosRoot.resolve("skills").resolve(skill + ".md");
      if (!Files.isRegularFile(file)) {
        throw new IllegalStateException("Profile " + profile.name() + " 引用的 Skill 文件不存在: " + skill);
      }
      context.append(read(file)).append('\n');
    }
    return context.toString();
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
