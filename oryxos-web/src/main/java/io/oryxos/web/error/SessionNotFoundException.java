package io.oryxos.web.error;

/** 会话不存在 → 404。由 Controller 在 get 未命中 / archive 返回 false 时抛出。 */
public class SessionNotFoundException extends RuntimeException {

  public SessionNotFoundException(String sessionId) {
    super("会话不存在: " + sessionId);
  }
}
