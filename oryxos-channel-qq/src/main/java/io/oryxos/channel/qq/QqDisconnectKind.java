package io.oryxos.channel.qq;

/** Gateway 断开原因：优雅重连可立即重试，突发断开走退避。 */
enum QqDisconnectKind {
  GRACEFUL,
  ABRUPT
}
