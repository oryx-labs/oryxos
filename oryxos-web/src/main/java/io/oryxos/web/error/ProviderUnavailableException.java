package io.oryxos.web.error;

/** 下游 Provider 不可用 → 503。深层 Provider 调用失败时上抛，Web 层统一翻译。 */
public class ProviderUnavailableException extends RuntimeException {

  public ProviderUnavailableException(String message) {
    super(message);
  }

  public ProviderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
