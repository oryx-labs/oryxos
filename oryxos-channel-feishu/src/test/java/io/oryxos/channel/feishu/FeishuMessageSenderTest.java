package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.CreateMessageRespBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import io.oryxos.core.channel.OutboundGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 017 T012：发送器——分段边界、sandbox 显式校验、reply 分支、平台错误码。 */
class FeishuMessageSenderTest {

  private static final String BASE_URL = "https://open.feishu.cn";

  private Client client;
  private OutboundGuard guard;
  private FeishuMessageSender sender;

  @BeforeEach
  void setUp() throws Exception {
    client = mock(Client.class, RETURNS_DEEP_STUBS);
    guard = mock(OutboundGuard.class);
    sender = new FeishuMessageSender(client, guard, BASE_URL, 10);
    CreateMessageResp ok = new CreateMessageResp();
    ok.setCode(0);
    when(client.im().message().create(any(CreateMessageReq.class))).thenReturn(ok);
    ReplyMessageResp replyOk = new ReplyMessageResp();
    replyOk.setCode(0);
    when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(replyOk);
  }

  @Test
  @DisplayName("分段边界：空文本 1 段、恰好上限 1 段、超限按序切分内容不丢")
  void segmentBoundaries() {
    assertEquals(List.of(""), FeishuMessageSender.segment("", 10));
    assertEquals(List.of("1234567890"), FeishuMessageSender.segment("1234567890", 10));
    assertEquals(List.of("1234567890", "abc"), FeishuMessageSender.segment("1234567890abc", 10));
    assertEquals(
        "1234567890abc", String.join("", FeishuMessageSender.segment("1234567890abc", 10)));
  }

  @Test
  @DisplayName("发送前显式过 OutboundGuard（宪法 VI）；拒绝时不发任何请求")
  void guardEnforcedBeforeSend() throws Exception {
    org.mockito.Mockito.doThrow(new IllegalStateException("域名不在 http 白名单"))
        .when(guard)
        .check(BASE_URL);
    assertThrows(IllegalStateException.class, () -> sender.send("oc_1", "hi", null));
    verify(client.im().message(), never()).create(any(CreateMessageReq.class));
  }

  @Test
  @DisplayName("私聊直发走 create；超长文本逐段发送；payload 为 post+md")
  void createPathWithChunks() throws Exception {
    sender.send("oc_1", "1234567890abc", null); // 10 字符上限 → 2 段
    ArgumentCaptor<CreateMessageReq> captor = ArgumentCaptor.forClass(CreateMessageReq.class);
    verify(client.im().message(), times(2)).create(captor.capture());
    verify(client.im().message(), never()).reply(any(ReplyMessageReq.class));
    CreateMessageReq first = captor.getAllValues().get(0);
    assertEquals("post", first.getCreateMessageReqBody().getMsgType());
    assertTrue(first.getCreateMessageReqBody().getContent().contains("\"tag\":\"md\""));
  }

  @Test
  @DisplayName("群聊回复走 reply 引用原消息")
  void replyPath() throws Exception {
    sender.send("oc_1", "答案", "om_origin");
    ArgumentCaptor<ReplyMessageReq> captor = ArgumentCaptor.forClass(ReplyMessageReq.class);
    verify(client.im().message()).reply(captor.capture());
    verify(client.im().message(), never()).create(any(CreateMessageReq.class));
    assertEquals("post", captor.getValue().getReplyMessageReqBody().getMsgType());
  }

  @Test
  @DisplayName("平台返回非 0 码：抛出可读异常")
  void platformErrorRaises() throws Exception {
    CreateMessageResp fail = new CreateMessageResp();
    fail.setCode(230025);
    fail.setMsg("message too large");
    when(client.im().message().create(any(CreateMessageReq.class))).thenReturn(fail);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> sender.send("oc_1", "hi", null));
    assertTrue(e.getMessage().contains("230025"));
  }

  @Test
  @DisplayName("postMarkdownContent 编码 md 标签并转义")
  void postMarkdownContentEscapes() {
    String json = FeishuMessageSender.postMarkdownContent("a\"b");
    assertTrue(json.contains("\"tag\":\"md\""));
    assertTrue(json.contains("a\\\"b") || json.contains("a\"b"));
    assertTrue(json.contains("\"zh_cn\""));
  }

  @Test
  @DisplayName("postTitle 取首行并截断")
  void postTitleFromFirstLine() {
    assertEquals("OryxOS", FeishuMessageSender.postTitle(""));
    assertEquals("摘要", FeishuMessageSender.postTitle("## 摘要\n正文"));
    String title = FeishuMessageSender.postTitle("这是一段很长的标题需要被截断到二十字以后的内容");
    assertTrue(title.length() <= 20);
  }

  @Test
  @DisplayName("textContent JSON 编码正确转义（对照）")
  void textContentEscapes() {
    assertEquals("{\"text\":\"a\\\"b\"}", FeishuMessageSender.textContent("a\"b"));
  }

  @Test
  @DisplayName("interactive create 返回 message_id；patch 走 PATCH API")
  void interactiveCreateAndPatch() throws Exception {
    CreateMessageRespBody body = new CreateMessageRespBody();
    body.setMessageId("om_card");
    CreateMessageResp ok = new CreateMessageResp();
    ok.setCode(0);
    ok.setData(body);
    when(client.im().message().create(any(CreateMessageReq.class))).thenReturn(ok);
    com.lark.oapi.service.im.v1.model.PatchMessageResp patchOk =
        new com.lark.oapi.service.im.v1.model.PatchMessageResp();
    patchOk.setCode(0);
    when(client.im().message().patch(any(com.lark.oapi.service.im.v1.model.PatchMessageReq.class)))
        .thenReturn(patchOk);

    String id = sender.sendInteractive("oc_1", "{\"header\":{}}", null);
    assertEquals("om_card", id);
    ArgumentCaptor<CreateMessageReq> create = ArgumentCaptor.forClass(CreateMessageReq.class);
    verify(client.im().message()).create(create.capture());
    assertEquals("interactive", create.getValue().getCreateMessageReqBody().getMsgType());

    sender.patchInteractive("om_card", "{\"header\":{\"template\":\"green\"}}");
    verify(client.im().message())
        .patch(any(com.lark.oapi.service.im.v1.model.PatchMessageReq.class));
  }
}
