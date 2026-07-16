package io.oryxos.web.error;

/** Agent 调用超过 60 秒上限 → 504。 */
public class AgentTimeoutException extends RuntimeException {

  public AgentTimeoutException(String message) {
    super(message);
  }
}
