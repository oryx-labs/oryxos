package io.oryxos.core.mcp;

import java.util.List;

/**
 * MCP server 的管理台契约（依赖倒置）：oryxos-web 只认这个接口，具体实现（读写 {@code mcp_servers.yaml}、维护连接、把工具注册进 {@code
 * ToolRegistry}）留给 oryxos-tool——同 {@code SandboxWhitelist} 的既有分层方式（web 不反向依赖 tool）。
 *
 * <p>增/改/删都是"持久化 + 即时生效"：加一个就立刻尝试连接并把它的工具注册进共享 {@code ToolRegistry}，删一个就立刻断开并注销它的工具， 不需要重启
 * OryxOS（30 节 WorkspaceWatcher 之于 Agent 目录的同款设计取向）。
 */
public interface McpServerAdmin {

  /** 当前已配置的全部 MCP server（不含连接状态，纯配置）。 */
  List<McpServerConfig> list();

  /** 新增一个 server：校验 name 唯一 + transport 合法 → 落盘 → 立即尝试连接。名字冲突抛 {@code IllegalArgumentException}。 */
  McpServerConfig add(McpServerConfig config);

  /** 更新一个 server：先断开旧连接 → 落盘 → 用新配置重连。 */
  McpServerConfig update(String name, McpServerConfig config);

  /** 删除一个 server：断开连接、注销它注册过的工具、从配置文件移除。不存在则幂等（不抛错）。 */
  void remove(String name);

  /** 全部 server 的运行时连接状态（是否连上、给了哪些工具、失败原因）。 */
  List<McpServerStatus> status();

  /** 内置的业界公开 MCP 目录，供"一键启用"选用。 */
  List<McpCatalogEntry> catalog();
}
