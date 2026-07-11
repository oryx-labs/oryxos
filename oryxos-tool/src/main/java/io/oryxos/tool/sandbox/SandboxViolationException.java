package io.oryxos.tool.sandbox;

/** 白名单校验不通过：动作被拦下、根本不会发生。 */
public class SandboxViolationException extends RuntimeException {

  public SandboxViolationException(String message) {
    super(message);
  }
}
