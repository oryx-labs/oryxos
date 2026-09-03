package io.oryxos.channel.feishu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.CreateMessageRespBody;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReqBody;
import com.lark.oapi.service.im.v1.model.PatchMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageRespBody;
import io.oryxos.core.channel.OutboundGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书回复发送器（017 T012）：经 SDK 调 im/v1/messages（tenant token 由 SDK 自动管理）。
 *
 * <p>契约规则 A3 在此实现：发送前显式过 {@link OutboundGuard}（渠道自建出站不会被沙箱自动拦截，必须主动接线，R7）；
 * 超长文本按可配段长分段顺序发送不丢内容（FR-009，平台请求体上限 150KB，默认段长 4000 字符留足 JSON 转义余量）； 每段带随机 uuid 幂等（平台 1 小时内同 uuid
 * 至多成功一条）；replyToMessageId 非空走 reply 引用原消息（B4）。
 *
 * <p>出站使用富文本 {@code post} + {@code md} 标签承载 Agent Markdown（对齐企微/钉钉出站）。 进度流另走 {@code interactive} 卡片
 * create + patch（#347）。
 */
public class FeishuMessageSender {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuMessageSender.class);

  static final int DEFAULT_CHUNK_SIZE = 4000;
  private static final String MSG_TYPE_POST = "post";
  private static final String MSG_TYPE_INTERACTIVE = "interactive";
  private static final String RECEIVE_ID_TYPE_CHAT = "chat_id";
  private static final String POST_LOCALE = "zh_cn";
  private static final String MD_TAG = "md";
  private static final String DEFAULT_POST_TITLE = "OryxOS";
  private static final String MARKDOWN_HEADING_PREFIX = "#";
  private static final int POST_TITLE_MAX_LEN = 20;

  private final Client client;
  private final OutboundGuard guard;
  private final String apiBaseUrl;
  private final int chunkSize;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "client 是适配器持有的 SDK 单例（token 缓存共享正是意图），构造注入共享引用。")
  public FeishuMessageSender(Client client, OutboundGuard guard, String apiBaseUrl, int chunkSize) {
    this.client = client;
    this.guard = guard;
    this.apiBaseUrl = apiBaseUrl;
    this.chunkSize = chunkSize;
  }

  /**
   * 发送 Markdown 回复（post + md）；超长自动分段顺序发送。
   *
   * @param chatId 目标会话（私聊/群一致用 chat_id）
   * @param text 回复正文（Agent Markdown 原文）
   * @param replyToMessageId 非空则引用该消息回复（群聊问答对应）
   */
  public void send(String chatId, String text, String replyToMessageId) {
    guard.check(apiBaseUrl); // 复用 http 域名白名单（宪法 VI：显式过沙箱）
    for (String chunk : segment(text, chunkSize)) {
      try {
        if (replyToMessageId == null) {
          sendCreate(chatId, chunk);
        } else {
          sendReply(replyToMessageId, chunk);
        }
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException("飞书消息发送失败: " + sanitize(e.getMessage()), e);
      }
    }
  }

  /**
   * 发送交互卡片并返回 {@code message_id}（供后续 patch）。群聊时 {@code replyToMessageId} 非空走 reply。
   *
   * @param cardJson 卡片 JSON（非再包一层 content）
   */
  public String sendInteractive(String chatId, String cardJson, String replyToMessageId) {
    guard.check(apiBaseUrl);
    try {
      if (replyToMessageId == null || replyToMessageId.isBlank()) {
        return createInteractive(chatId, cardJson);
      }
      return replyInteractive(replyToMessageId, cardJson);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("飞书交互卡片发送失败: " + sanitize(e.getMessage()), e);
    }
  }

  /** 更新已发出的交互卡片内容（PATCH）。 */
  public void patchInteractive(String messageId, String cardJson) {
    if (messageId == null || messageId.isBlank()) {
      throw new IllegalArgumentException("messageId 不能为空");
    }
    guard.check(apiBaseUrl);
    try {
      PatchMessageResp resp =
          client
              .im()
              .message()
              .patch(
                  PatchMessageReq.newBuilder()
                      .messageId(messageId)
                      .patchMessageReqBody(
                          PatchMessageReqBody.newBuilder().content(cardJson).build())
                      .build());
      requireSuccess(resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("飞书交互卡片更新失败: " + sanitize(e.getMessage()), e);
    }
  }

  private String createInteractive(String chatId, String cardJson) throws Exception {
    CreateMessageResp resp =
        client
            .im()
            .message()
            .create(
                CreateMessageReq.newBuilder()
                    .receiveIdType(RECEIVE_ID_TYPE_CHAT)
                    .createMessageReqBody(
                        CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType(MSG_TYPE_INTERACTIVE)
                            .content(cardJson)
                            .uuid(UUID.randomUUID().toString())
                            .build())
                    .build());
    requireSuccess(resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
    CreateMessageRespBody data = resp == null ? null : resp.getData();
    String id = data == null ? null : data.getMessageId();
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("飞书交互卡片发送成功但未返回 message_id");
    }
    return id;
  }

  private String replyInteractive(String replyToMessageId, String cardJson) throws Exception {
    ReplyMessageResp resp =
        client
            .im()
            .message()
            .reply(
                ReplyMessageReq.newBuilder()
                    .messageId(replyToMessageId)
                    .replyMessageReqBody(
                        ReplyMessageReqBody.newBuilder()
                            .msgType(MSG_TYPE_INTERACTIVE)
                            .content(cardJson)
                            .uuid(UUID.randomUUID().toString())
                            .build())
                    .build());
    requireSuccess(resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
    ReplyMessageRespBody data = resp == null ? null : resp.getData();
    String id = data == null ? null : data.getMessageId();
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("飞书交互卡片回复成功但未返回 message_id");
    }
    return id;
  }

  private void sendCreate(String chatId, String chunk) throws Exception {
    CreateMessageResp resp =
        client
            .im()
            .message()
            .create(
                CreateMessageReq.newBuilder()
                    .receiveIdType(RECEIVE_ID_TYPE_CHAT)
                    .createMessageReqBody(
                        CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType(MSG_TYPE_POST)
                            .content(postMarkdownContent(chunk))
                            .uuid(UUID.randomUUID().toString())
                            .build())
                    .build());
    requireSuccess(resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
  }

  private void sendReply(String replyToMessageId, String chunk) throws Exception {
    ReplyMessageResp resp =
        client
            .im()
            .message()
            .reply(
                ReplyMessageReq.newBuilder()
                    .messageId(replyToMessageId)
                    .replyMessageReqBody(
                        ReplyMessageReqBody.newBuilder()
                            .msgType(MSG_TYPE_POST)
                            .content(postMarkdownContent(chunk))
                            .uuid(UUID.randomUUID().toString())
                            .build())
                    .build());
    requireSuccess(resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
  }

  private static void requireSuccess(Integer code, String msg) {
    if (code == null || code != 0) {
      throw new IllegalStateException("飞书消息发送失败 code=" + code + " msg=" + sanitize(msg));
    }
  }

  /**
   * 富文本 post content：zh_cn + md 标签承载 Markdown（官方推荐发 MD 的方式）。
   *
   * <p>结构：{@code {"zh_cn":{"title":"...","content":[[{"tag":"md","text":"..."}]]}}}
   */
  static String postMarkdownContent(String text) {
    String body = text == null ? "" : text;
    JsonObject md = new JsonObject();
    md.addProperty("tag", MD_TAG);
    md.addProperty("text", body);
    JsonArray line = new JsonArray();
    line.add(md);
    JsonArray content = new JsonArray();
    content.add(line);
    JsonObject locale = new JsonObject();
    locale.addProperty("title", postTitle(body));
    locale.add("content", content);
    JsonObject root = new JsonObject();
    root.add(POST_LOCALE, locale);
    return root.toString();
  }

  /** 会话列表透出标题：取首行摘要，过长截断。 */
  static String postTitle(String content) {
    if (content == null || content.isBlank()) {
      return DEFAULT_POST_TITLE;
    }
    String line = content.stripLeading();
    int newline = line.indexOf('\n');
    if (newline >= 0) {
      line = line.substring(0, newline);
    }
    line = line.strip();
    if (line.startsWith(MARKDOWN_HEADING_PREFIX)) {
      line = line.replaceFirst("^#+\\s*", "");
    }
    if (line.length() > POST_TITLE_MAX_LEN) {
      line = line.substring(0, POST_TITLE_MAX_LEN);
    }
    return line.isBlank() ? DEFAULT_POST_TITLE : line;
  }

  /** 旧 text 编码（对照用）；出站已改用 {@link #postMarkdownContent}。 */
  static String textContent(String text) {
    JsonObject obj = new JsonObject();
    obj.addProperty("text", text);
    return obj.toString();
  }

  /** 按字符数分段，顺序保持、内容不丢；空文本发送一条空段（保持"必有回复"语义）。 */
  static List<String> segment(String text, int chunkSize) {
    List<String> chunks = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      chunks.add("");
      return chunks;
    }
    for (int i = 0; i < text.length(); i += chunkSize) {
      chunks.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
    }
    if (chunks.size() > 1) {
      LOG.info("回复超长，按 {} 字符分 {} 段发送", chunkSize, chunks.size());
    }
    return chunks;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
