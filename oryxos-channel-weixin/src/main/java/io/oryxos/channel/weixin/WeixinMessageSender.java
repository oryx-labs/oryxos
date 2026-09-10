package io.oryxos.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** iLink {@code sendmessage} 文本回复。 */
public class WeixinMessageSender {

  private final WeixinIlinkClient client;

  public WeixinMessageSender(WeixinIlinkClient client) {
    this.client = client;
  }

  public void sendReply(String toUserId, String text, String contextToken) {
    try {
      JsonNode resp = client.sendText(toUserId, text, contextToken, UUID.randomUUID().toString());
      int ret = resp.path("ret").asInt(0);
      int errcode = resp.path("errcode").asInt(0);
      if (ret != 0 || errcode != 0) {
        throw new IllegalStateException(
            "微信发信失败 ret="
                + ret
                + " errcode="
                + errcode
                + " errmsg="
                + sanitize(resp.path("errmsg").asText("")));
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("微信发信失败: " + e.getMessage(), e);
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
