package io.oryxos.channel.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionResp;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionReq;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionResp;
import com.lark.oapi.service.im.v1.model.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书消息表情管理器：为消息添加/移除 emoji reaction。
 *
 * <p>用于"已读确认"场景：收到用户消息时立即添加⌨️表情，处理完成后移除。
 *
 * <p>API
 * 文档：https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message-reaction/create
 */
public class FeishuReactionManager {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuReactionManager.class);

  /** 键盘表情（表示"正在打字/处理"） */
  private static final String EMOJI_KEYBOARD = "KEYBOARD";

  private final Client client;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "client 是适配器持有的 SDK 单例，共享引用正是意图")
  public FeishuReactionManager(Client client) {
    this.client = client;
  }

  /**
   * 为消息添加⌨️表情。
   *
   * @param messageId 消息 ID
   * @return reaction ID（用于后续删除），失败返回 null
   */
  public String addKeyboardReaction(String messageId) {
    try {
      Emoji emoji = new Emoji();
      emoji.setEmojiType(EMOJI_KEYBOARD);

      CreateMessageReactionReqBody body =
          CreateMessageReactionReqBody.newBuilder().reactionType(emoji).build();

      CreateMessageReactionReq req =
          CreateMessageReactionReq.newBuilder()
              .messageId(messageId)
              .createMessageReactionReqBody(body)
              .build();

      CreateMessageReactionResp resp = client.im().messageReaction().create(req);

      if (resp == null || resp.getCode() != 0) {
        LOG.warn(
            "飞书添加表情失败 messageId={} code={} msg={}",
            sanitize(messageId),
            resp == null ? null : resp.getCode(),
            resp == null ? null : sanitize(resp.getMsg()));
        return null;
      }

      String reactionId =
          resp.getData() == null || resp.getData().getReactionId() == null
              ? null
              : resp.getData().getReactionId();

      LOG.debug("飞书添加表情成功 messageId={} reactionId={}", sanitize(messageId), sanitize(reactionId));
      return reactionId;

    } catch (Exception e) {
      LOG.warn("飞书添加表情异常 messageId={}: {}", sanitize(messageId), sanitize(e.getMessage()));
      return null;
    }
  }

  /**
   * 移除表情。
   *
   * @param messageId 消息 ID
   * @param reactionId reaction ID（从 addKeyboardReaction 返回）
   * @return 是否成功移除
   */
  public boolean removeReaction(String messageId, String reactionId) {
    if (reactionId == null) {
      LOG.debug("reactionId 为 null，跳过移除表情");
      return false;
    }

    try {
      DeleteMessageReactionReq req =
          DeleteMessageReactionReq.newBuilder().messageId(messageId).reactionId(reactionId).build();

      DeleteMessageReactionResp resp = client.im().messageReaction().delete(req);

      if (resp == null || resp.getCode() != 0) {
        LOG.warn(
            "飞书移除表情失败 messageId={} reactionId={} code={} msg={}",
            sanitize(messageId),
            sanitize(reactionId),
            resp == null ? null : resp.getCode(),
            resp == null ? null : sanitize(resp.getMsg()));
        return false;
      }

      LOG.debug("飞书移除表情成功 messageId={} reactionId={}", sanitize(messageId), sanitize(reactionId));
      return true;

    } catch (Exception e) {
      LOG.warn(
          "飞书移除表情异常 messageId={} reactionId={}: {}",
          sanitize(messageId),
          sanitize(reactionId),
          sanitize(e.getMessage()));
      return false;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
