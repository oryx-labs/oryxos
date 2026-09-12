package io.oryxos.channel.weixinmini;

/** 小程序出站 API（MVP 仅文本客服消息）。 */
interface WeixinMiniClient {

  void sendText(String openId, String text);
}
