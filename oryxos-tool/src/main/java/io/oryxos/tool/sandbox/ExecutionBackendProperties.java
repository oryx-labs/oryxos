package io.oryxos.tool.sandbox;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行后端配置（键 {@code oryxos.sandbox.execution.*}，024 容器级执行隔离）。
 *
 * <p>术语约定（spec）：execution 词根专指执行位置抽象，与白名单沙箱（{@code file.*}/{@code shell.*}/{@code http.*}）相区分。默认
 * local 档零行为变化（SC-001 锚点）；docker 档必填 {@code image} 的校验由启动检查把关（FR-005，fail loud）。本类做 null/空白归一化默认值，
 * 并把 backend 限死在 {@link #BACKENDS}：拼错或大小写不符的值以前谁都不认，最后静默落到 local 在宿主上执行。
 */
@ConfigurationProperties(prefix = "oryxos.sandbox.execution")
public record ExecutionBackendProperties(
    String backend, String image, String memory, String cpus, String network, String user) {

  /** 默认档：local——不配即现状，行为零变化。 */
  public static final String DEFAULT_BACKEND = "local";

  /** 默认资源限额与安全参数（RQ-5：安全优先而非性能优先）。 */
  public static final String DEFAULT_MEMORY = "512m";

  public static final String DEFAULT_CPUS = "1.0";
  public static final String DEFAULT_NETWORK = "none";
  public static final String DEFAULT_USER = "65534:65534";
  public static final String DOCKER_BACKEND = "docker";

  public static final String SSH_BACKEND = "ssh";

  /** 合法 backend 全集：{@link #isDocker()}/{@link #isSsh()} 都不认的值不是"第三种档"，是写错了。 */
  public static final Set<String> BACKENDS = Set.of(DEFAULT_BACKEND, DOCKER_BACKEND, SSH_BACKEND);

  public ExecutionBackendProperties {
    backend = normalize(backend, DEFAULT_BACKEND);
    if (!BACKENDS.contains(backend)) {
      // 静默退化最危险的方向是"以为在容器里、其实在宿主上"——同 ProfileLoader 对 sandbox.backend 的点名口径。
      throw new IllegalArgumentException(
          "oryxos.sandbox.execution.backend 非法值 '"
              + backend
              + "'（仅认 local/docker/ssh），未识别的值不会启用任何隔离");
    }
    image = normalize(image, "");
    memory = normalize(memory, DEFAULT_MEMORY);
    cpus = normalize(cpus, DEFAULT_CPUS);
    network = normalize(network, DEFAULT_NETWORK);
    user = normalize(user, DEFAULT_USER);
  }

  /** docker 档判断的唯一权威口径（装配与启动校验共用，避免字符串散落）。 */
  public boolean isDocker() {
    return DOCKER_BACKEND.equals(backend);
  }

  public boolean isSsh() {
    return SSH_BACKEND.equals(backend);
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
