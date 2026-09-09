package io.oryxos.web.controller;

import io.oryxos.core.execution.ExecutionBackendSnapshot;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.common.ApiResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行后端状态页 API（024 US3 / FR-012，只读）：全局档位、docker 可用性（按需探测 D5——无常驻心跳）、 生效镜像与限额、各 Agent 的 frontmatter
 * 覆写一览。配置修改走 application.yml / AGENT.md（GitOps 路径），本 API 不提供写操作。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API is unauthenticated by design (internal network + gateway).")
@RestController
@RequestMapping("/api/v1/sandbox/execution")
public class ExecutionBackendController {

  private static final Logger LOG = LoggerFactory.getLogger(ExecutionBackendController.class);

  /** 探测命令输出截断（诊断够用，防刷屏）。 */
  private static final int DIAGNOSTIC_TAIL = 300;

  private static final String DOCKER = "docker";

  private final ExecutionBackendSnapshot props;
  private final ProfileRegistry profileRegistry;
  private final DockerStatusProbe probe;

  public ExecutionBackendController(
      ExecutionBackendSnapshot props, ProfileRegistry profileRegistry) {
    this(props, profileRegistry, ExecutionBackendController::processProbe);
  }

  ExecutionBackendController(
      ExecutionBackendSnapshot props, ProfileRegistry profileRegistry, DockerStatusProbe probe) {
    this.props = Objects.requireNonNull(props, "props 不能为空");
    this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry 不能为空");
    this.probe = Objects.requireNonNull(probe, "probe 不能为空");
  }

  /**
   * 探针：exit==0 返回 {@code null}（通过，stdout 供版本展示可另取）；否则返回输出尾部（诊断）。 启动失败返回错误信息而非抛异常——状态页语义是「如实呈现」，不
   * fail。
   */
  @FunctionalInterface
  interface DockerStatusProbe {
    String probe(List<String> argv);
  }

  /** 生产探针（argv 形式、命令全部为内部常量，非模型/用户输入）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "argv 全部来自内部常量与管理员配置的镜像名（非模型/用户输入），argv 直传不经 shell")
  private static String processProbe(List<String> argv) {
    try {
      Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
      byte[] output;
      try (InputStream in = process.getInputStream()) {
        output = in.readAllBytes();
      }
      if (process.waitFor() != 0) {
        return tail(output);
      }
      return null;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return e.getMessage() == null ? "probe failed" : e.getMessage();
    }
  }

  @GetMapping("/status")
  public ApiResponse<StatusView> status() {
    return ApiResponse.ok(
        new StatusView(
            props.backend(),
            dockerStatus(),
            props.image(),
            imageDigest(),
            props.memory(),
            props.cpus(),
            props.network(),
            props.user(),
            agentOverrides()));
  }

  private DockerStatusView dockerStatus() {
    String cliError = probe.probe(List.of(DOCKER, "--version"));
    if (cliError != null) {
      return new DockerStatusView(false, null, "CLI 不可用: " + cliError);
    }
    String infoError = probe.probe(List.of(DOCKER, "info"));
    if (infoError != null) {
      return new DockerStatusView(false, null, "daemon 不可达: " + infoError);
    }
    String version = probeVersion();
    return new DockerStatusView(true, version, null);
  }

  /** docker --version 的 stdout（如 "Docker version 28.4.0, build xxx"）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "argv 为内部常量（docker --version），非模型/用户输入，argv 直传不经 shell")
  private String probeVersion() {
    try {
      Process process = new ProcessBuilder(DOCKER, "--version").redirectErrorStream(true).start();
      byte[] output;
      try (InputStream in = process.getInputStream()) {
        output = in.readAllBytes();
      }
      return process.waitFor() == 0 ? new String(output, StandardCharsets.UTF_8).trim() : null;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }

  /** 镜像 digest（daemon 可达且 backend 配了镜像时才有值）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "argv 为内部常量与管理员配置的镜像名（非模型/用户输入），argv 直传不经 shell")
  private String imageDigest() {
    if (!props.isDocker() || props.image().isBlank()) {
      return null;
    }
    // inspect 通过时 stdout 即 digest（RepoDigests 首个），失败返回 null（镜像未拉取）
    try {
      Process process =
          new ProcessBuilder(DOCKER, "image", "inspect", "--format", "{{.Id}}", props.image())
              .redirectErrorStream(true)
              .start();
      byte[] output;
      try (InputStream in = process.getInputStream()) {
        output = in.readAllBytes();
      }
      return process.waitFor() == 0 ? new String(output, StandardCharsets.UTF_8).trim() : null;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.debug("镜像 digest 探测失败", e); // throwable 直传（栈帧由 logback 格式化，无 CRLF 注入面）
      return null;
    }
  }

  /** frontmatter 声明了 sandbox 段的 Agent 一览（未声明的不列——绝大多数继承全局）。 */
  private List<AgentOverrideView> agentOverrides() {
    return profileRegistry.all().stream()
        .filter(profile -> profile.sandbox() != null)
        .map(AgentOverrideView::from)
        .toList();
  }

  private static String tail(byte[] output) {
    String text = new String(output, StandardCharsets.UTF_8).replace('\r', '_').replace('\n', '_');
    return text.length() > DIAGNOSTIC_TAIL
        ? "…" + text.substring(text.length() - DIAGNOSTIC_TAIL)
        : text;
  }

  /** 状态总览。docker 可达性三态：CLI 缺失 / daemon 不可达 / 就绪（附版本）。 */
  public record StatusView(
      String backend,
      DockerStatusView docker,
      String image,
      String imageDigest,
      String memory,
      String cpus,
      String network,
      String user,
      List<AgentOverrideView> agentOverrides) {

    public StatusView {
      agentOverrides = agentOverrides == null ? List.of() : List.copyOf(agentOverrides);
    }
  }

  public record DockerStatusView(boolean reachable, String version, String error) {}

  public record AgentOverrideView(String agent, String backend, String memory, String cpus) {

    static AgentOverrideView from(Profile profile) {
      return new AgentOverrideView(
          profile.name(),
          profile.sandbox().backend(),
          profile.sandbox().memory(),
          profile.sandbox().cpus());
    }
  }
}
