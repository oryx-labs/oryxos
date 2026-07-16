package io.oryxos.web.error;

/** 通用资源不存在 → 404（如 invoke 一个不存在的 Agent）。 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
