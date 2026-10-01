package io.oryxos.channel.slack;

/** Slack Socket Mode 断线原因。 */
enum SlackDisconnectKind {
  /** 服务端 disconnect 帧（计划内，应立即重连）。 */
  GRACEFUL,
  /** 异常 / 对端关闭。 */
  ABRUPT
}
