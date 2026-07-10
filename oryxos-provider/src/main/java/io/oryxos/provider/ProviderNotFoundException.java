package io.oryxos.provider;

/** Profile 引用的 provider 名不在显式映射表里——点名报错，绝不静默换家。 */
public class ProviderNotFoundException extends RuntimeException {

  public ProviderNotFoundException(String providerName) {
    super("未知的 provider: " + providerName + "，请检查 Profile 与全局 oryxos.providers 配置");
  }
}
