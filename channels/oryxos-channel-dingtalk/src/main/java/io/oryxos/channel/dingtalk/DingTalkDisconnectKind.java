package io.oryxos.channel.dingtalk;

/** 钉钉 Stream 断线原因：服务端轮换 disconnect 与异常/对端关闭。 */
enum DingTalkDisconnectKind {
  /** 服务端 SYSTEM disconnect 帧（计划内轮换，应立即重连）。 */
  GRACEFUL,
  /** onClose / onError 等非计划断线。 */
  ABRUPT
}
