package io.oryxos.channel.discord;

/** Discord Gateway 断线原因：服务端要求重连 vs 异常断开。 */
enum DiscordDisconnectKind {
  /** op=7 Reconnect 或可预期关闭，立即重连。 */
  GRACEFUL,
  /** 网络错误 / 异常 close，指数退避重连。 */
  ABRUPT
}
