package io.oryxos.core.agent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Agent 的统一安全名称。
 *
 * <p>{@link #value()} 保留用户定义的精确大小写，供目录和 Profile 身份比较；{@link #lockKey()}
 * 仅用于进程内锁表，避免大小写不敏感文件系统为同一路径创建多把锁。
 */
public record AgentName(String value) {

  private static final int MAX_NAME_CHARS = 64;
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  public AgentName {
    if (value == null || value.length() > MAX_NAME_CHARS || !SAFE_NAME.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "非法 Agent 名（长度 1-64，只允许字母/数字/下划线/连字符）: " + sanitize(value));
    }
  }

  public static AgentName parse(String value) {
    return new AgentName(value);
  }

  /** 从目录的最后一个路径段解析 Agent 名；不接受文件系统根等没有 basename 的路径。 */
  public static AgentName fromDirectory(Path agentDir) {
    if (agentDir == null) {
      throw new IllegalArgumentException("Agent 目录缺少 basename");
    }
    Path basename = agentDir.getFileName();
    if (basename == null) {
      throw new IllegalArgumentException("Agent 目录缺少 basename");
    }
    return parse(basename.toString());
  }

  /** Profile.name 必须与目录 basename 逐字符一致，大小写不同也视为错误。 */
  public void requireProfileName(String profileName) {
    AgentName parsedProfile = parse(profileName);
    if (!value.equals(parsedProfile.value)) {
      throw new IllegalArgumentException(
          "Agent 目录名与 Profile.name 不一致: 目录=" + value + ", Profile=" + parsedProfile.value);
    }
  }

  /**
   * Requires the requested spelling to equal the directory entry stored by the filesystem. This
   * sibling lookup is stronger than lexical Path equality on case-insensitive filesystems.
   */
  public void requireFilesystemDirectoryName(Path agentDir) {
    if (agentDir == null) {
      throw new IllegalArgumentException("Agent 目录缺少父目录");
    }
    Path parent = agentDir.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Agent 目录缺少父目录");
    }
    try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parent)) {
      for (Path sibling : siblings) {
        boolean sameFile;
        try {
          sameFile = Files.isSameFile(sibling, agentDir);
        } catch (IOException ignored) {
          continue;
        }
        if (sameFile) {
          AgentName actual = fromDirectory(sibling);
          if (!value.equals(actual.value())) {
            throw new IllegalArgumentException(
                "Agent 请求名与实际目录名不一致: 请求=" + value + ", 目录=" + actual.value());
          }
          return;
        }
      }
      throw new IllegalArgumentException("Agent 目录身份无法确认: " + value);
    } catch (IOException error) {
      throw new IllegalArgumentException("Agent 目录身份无法确认: " + value, error);
    }
  }

  /** 只用于锁注册表；合法名称限定为 ASCII，因此 Locale.ROOT 小写化是确定性的。 */
  public String lockKey() {
    return value.toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return value;
  }

  private static String sanitize(String value) {
    return value == null ? "null" : value.replace('\r', '_').replace('\n', '_');
  }
}
