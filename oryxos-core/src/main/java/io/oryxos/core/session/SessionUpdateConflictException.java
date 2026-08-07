package io.oryxos.core.session;

/** 会话在处理期间已被其他执行更新；拒绝用旧快照覆盖较新的历史。 */
public class SessionUpdateConflictException extends RuntimeException {

  public SessionUpdateConflictException(String sessionId) {
    super("会话已被其他请求更新，请重新获取后重试: " + sessionId);
  }
}
