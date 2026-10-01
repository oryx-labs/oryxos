package io.oryxos.channel.weixinmp;

/** 服务号出站 API（MVP 仅文本客服消息）。 */
interface WeixinMpClient {

  void sendText(String openId, String text);
}
