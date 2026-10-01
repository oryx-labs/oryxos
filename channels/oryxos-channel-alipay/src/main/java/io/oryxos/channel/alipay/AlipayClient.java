package io.oryxos.channel.alipay;

interface AlipayClient {

  void sendText(String toUserId, String text);
}
