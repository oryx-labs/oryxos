package io.oryxos.core.profile;

/** Profile 合法性校验失败——消息必须点名具体文件与原因，绝不静默。 */
public class ProfileValidationException extends RuntimeException {

  public ProfileValidationException(String message) {
    super(message);
  }
}
