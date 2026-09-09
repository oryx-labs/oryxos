package io.oryxos.tool.sandbox;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.profile.Profile;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 按 Agent 选档的执行后端入口（024 US2 / 设计决策 D8）：全局配置为基线，frontmatter sandbox 段按 Agent 覆写 backend 与
 * memory/cpus——生效档 = Agent 覆写 > 全局。Agent 身份取 {@link ToolExecutionContext#agentName()} （ToolExecutor
 * 执行前置入、同线程可见；非 Agent 触发的调用按全局档）。
 *
 * <p>local 生效档委托固定 local 实例（不置审计上下文，行为与 Phase 3 前完全一致）；docker 生效档按生效配置 现建
 * DockerProcessStarter（轻对象，无状态可现建）。
 */
public final class AgentAwareProcessStarter implements ProcessStarter {

  private final ExecutionBackendProperties global;
  private final Function<String, Profile.Sandbox> agentSandboxLookup;
  private final ProcessStarter local;
  private final Function<ExecutionBackendProperties, ProcessStarter> dockerFactory;

  public AgentAwareProcessStarter(
      ExecutionBackendProperties global,
      Function<String, Profile.Sandbox> agentSandboxLookup,
      ProcessStarter local,
      Function<ExecutionBackendProperties, ProcessStarter> dockerFactory) {
    this.global = Objects.requireNonNull(global, "global 不能为空");
    this.agentSandboxLookup = Objects.requireNonNull(agentSandboxLookup, "agentSandboxLookup 不能为空");
    this.local = Objects.requireNonNull(local, "local 不能为空");
    this.dockerFactory = Objects.requireNonNull(dockerFactory, "dockerFactory 不能为空");
  }

  @Override
  public Process start(List<String> command) throws IOException {
    ExecutionBackendProperties effective = resolve(ToolExecutionContext.agentName());
    return effective.isDocker()
        ? dockerFactory.apply(effective).start(command)
        : local.start(command);
  }

  /**
   * 生效配置收敛（D8 固定优先级）：backend/memory/cpus 逐项「Agent 覆写 > 全局」；镜像/网络/user 不可覆写 （镜像属管理员治理域，与 020
   * 「治理与作者分离」同构）。
   */
  ExecutionBackendProperties resolve(String agentName) {
    Profile.Sandbox override = agentName == null ? null : agentSandboxLookup.apply(agentName);
    if (override == null) {
      return global;
    }
    String backend = override.backend() == null ? global.backend() : override.backend();
    String memory = override.memory() == null ? global.memory() : override.memory();
    String cpus = override.cpus() == null ? global.cpus() : override.cpus();
    return new ExecutionBackendProperties(
        backend, global.image(), memory, cpus, global.network(), global.user());
  }
}
