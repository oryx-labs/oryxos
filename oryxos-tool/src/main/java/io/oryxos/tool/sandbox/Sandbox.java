package io.oryxos.tool.sandbox;

/**
 * 工具安全校验入口（宪法 VI）：每个涉外工具执行的第一步调用 enforce，校验不过抛 {@link
 * SandboxViolationException}、动作零发生。白名单实现（WhitelistSandbox）归 24 节交付， 本接口 20
 * 节先接入执行链——课件明文"先以接口调用形式接入"。
 */
public interface Sandbox {

  void enforce(SandboxAction action);
}
