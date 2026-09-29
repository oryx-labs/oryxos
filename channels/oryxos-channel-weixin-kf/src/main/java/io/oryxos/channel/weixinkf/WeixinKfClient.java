package io.oryxos.channel.weixinkf;

/** 微信客服 OpenAPI 面（便于单测注入）。 */
interface WeixinKfClient {

  WeixinKfSyncResult syncMsg(String openKfid, String callbackToken, String cursor);

  void sendText(String openKfid, String externalUserId, String text);

  void ensureAiReception(String openKfid, String externalUserId);

  /** {@code GET /cgi-bin/media/get} 临时素材。 */
  WeixinKfMediaBlob downloadMedia(String mediaId);
}
