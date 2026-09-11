package io.oryxos.core.execution;

/**
 * 执行后端配置快照（024 US3，跨模块契约）：web 状态页需要呈现的执行后端配置——由装配方（oryxos-cli）从 oryxos-tool 的 {@code
 * ExecutionBackendProperties} 转换注入。 web 不依赖 tool 模块（分层约束），故契约置 core（ToolPolicyService 同款先例：跨模块契约放
 * core、实现/配置在下层模块）。
 *
 * @param backend local / docker
 * @param image 执行镜像（docker 档必填，local 为空）
 * @param memory 默认内存限额（如 512m）
 * @param cpus 默认 CPU 限额（如 1.0）
 * @param network 网络模式（默认 none）
 * @param user 容器执行用户（默认 nobody）
 */
public record ExecutionBackendSnapshot(
    String backend, String image, String memory, String cpus, String network, String user) {

  public boolean isDocker() {
    return "docker".equals(backend);
  }
}
