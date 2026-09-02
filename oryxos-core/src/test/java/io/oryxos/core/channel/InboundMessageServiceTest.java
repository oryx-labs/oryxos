package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 017 契约行为 B1~B10 的编排层单测（契约测试集的行为基线，参数化两档见 InboundMessageServiceContractTest）。 */
class InboundMessageServiceTest {

  private static final String AGENT = "ops-agent";

  private AgentService agentService;
  private SessionManager sessionManager;
  private ProfileRegistry profileRegistry;
  private AgentExecutionService executionService;
  private StubChannelAdapter adapter;
  private InboundMessageService service;

  @BeforeEach
  void setUp() {
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    profileRegistry = mock(ProfileRegistry.class);
    executionService = mock(AgentExecutionService.class);
    adapter = new StubChannelAdapter("stub-chan", AGENT);
    service =
        new InboundMessageService(
            agentService,
            sessionManager,
            profileRegistry,
            executionService,
            new MessageDeduplicator(),
            null,
            Duration.ofMillis(120));
    when(profileRegistry.get(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    // 默认：triggerAsync 同步执行 work（吞掉 work 异常，模拟真实实现里的记录失败不上抛）
    doAnswer(
            inv -> {
              try {
                ((Runnable) inv.getArgument(3)).run();
              } catch (RuntimeException ignored) {
                // 真实 AgentExecutionService 捕获后落失败记录
              }
              return 1L;
            })
        .when(executionService)
        .triggerAsync(anyString(), anyString(), any(), any());
  }

  private InboundMessage p2p(String messageId, String content) {
    return new InboundMessage(
        "stub",
        "stub-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        "chat-p2p",
        content,
        true,
        false,
        java.util.List.of());
  }

  private InboundMessage group(String messageId, String content) {
    return new InboundMessage(
        "stub",
        "stub-chan",
        messageId,
        ChatKind.GROUP,
        "user-1",
        "chat-grp",
        content,
        true,
        true,
        java.util.List.of());
  }

  @Test
  @DisplayName("B1: 同一 message_id 重复到达只处理一次，用户只收到一条回答")
  void duplicateMessageProcessedOnce() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(eq(session), eq("hi"), anyList())).thenReturn("回答");

    service.onMessage(p2p("m-1", "hi"), adapter);
    service.onMessage(p2p("m-1", "hi"), adapter);

    verify(agentService, times(1)).process(eq(session), eq("hi"), anyList());
    assertEquals(1, adapter.sent().size());
  }

  @Test
  @DisplayName("B2/B4: 私聊走三元组持久会话，回复直发不引用")
  void p2pUsesPersistentSession() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(eq(session), eq("磁盘告警怎么处理"), anyList())).thenReturn("先看 df -h");

    service.onMessage(p2p("m-2", "磁盘告警怎么处理"), adapter);

    verify(sessionManager).getOrCreate("stub", "user-1", AGENT);
    assertEquals(1, adapter.sent().size());
    assertEquals("先看 df -h", adapter.sent().get(0).text());
    assertEquals("chat-p2p", adapter.sent().get(0).chatId());
    assertEquals(null, adapter.sent().get(0).replyToMessageId());
  }

  @Test
  @DisplayName("B3/B4/B10: 群聊走无状态问答（渠道前缀临时会话 id），回复引用原消息，不落持久会话")
  void groupIsStatelessWithChannelTag() {
    when(agentService.processStateless(
            eq(AGENT), eq("发布为什么回滚"), anyList(), startsWith("stub-group:")))
        .thenReturn("因为配置漂移");

    service.onMessage(group("m-3", "发布为什么回滚"), adapter);

    verify(agentService)
        .processStateless(eq(AGENT), eq("发布为什么回滚"), anyList(), startsWith("stub-group:"));
    verifyNoInteractions(sessionManager);
    assertEquals(1, adapter.sent().size());
    assertEquals("m-3", adapter.sent().get(0).replyToMessageId());
    verify(executionService)
        .triggerAsync(eq(AGENT), eq("stub"), startsWith("stub-group:"), any(Runnable.class));
  }

  @Test
  @DisplayName("B6: 推理失败以可读消息回复用户，不静默")
  void failureRepliedReadably() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(any(), anyString(), anyList()))
        .thenThrow(new IllegalStateException("模型服务不可用"));

    service.onMessage(p2p("m-4", "hi"), adapter);

    assertEquals(1, adapter.sent().size());
    assertEquals(InboundMessageService.FAILURE_REPLY, adapter.sent().get(0).text());
  }

  @Test
  @DisplayName("B7: 不可处理非文本消息回能力说明，不触发推理与执行记录")
  void nonTextualGetsCapabilityNotice() {
    InboundMessage img =
        new InboundMessage(
            "stub",
            "stub-chan",
            "m-5",
            ChatKind.P2P,
            "user-1",
            "chat-p2p",
            "",
            false,
            false,
            java.util.List.of());

    service.onMessage(img, adapter);

    assertEquals(1, adapter.sent().size());
    assertEquals(InboundMessageService.UNSUPPORTED_TYPE_REPLY, adapter.sent().get(0).text());
    verifyNoInteractions(agentService);
    verify(executionService, never()).triggerAsync(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("B7b: 图片附件消息进入 Agent 编排")
  void imageAttachmentProcessed() {
    InboundMessage img =
        new InboundMessage(
            "stub",
            "stub-chan",
            "m-5b",
            ChatKind.P2P,
            "user-1",
            "chat-p2p",
            "",
            false,
            false,
            java.util.List.of(InboundAttachment.imageUrl("https://example/img.png")));
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(eq(session), anyString(), anyList())).thenReturn("图片已收到");

    service.onMessage(img, adapter);

    verify(agentService).process(eq(session), anyString(), anyList());
    assertEquals("图片已收到", adapter.sent().get(0).text());
  }

  @Test
  @DisplayName("B8: 超过阈值仍未完成时先行发送处理中提示，最终回答照发")
  void processingNoticeSentWhenSlow() throws Exception {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    CountDownLatch workDone = new CountDownLatch(1);
    when(agentService.process(any(), anyString(), anyList()))
        .thenAnswer(
            inv -> {
              Thread.sleep(400); // 超过 120ms 阈值
              return "慢回答";
            });
    // 覆盖默认：work 放到真实虚拟线程跑，模拟异步时序
    doAnswer(
            inv -> {
              Thread.ofVirtual()
                  .start(
                      () -> {
                        try {
                          ((Runnable) inv.getArgument(3)).run();
                        } finally {
                          workDone.countDown();
                        }
                      });
              return 1L;
            })
        .when(executionService)
        .triggerAsync(anyString(), anyString(), any(), any());

    service.onMessage(p2p("m-6", "慢问题"), adapter);

    assertTrue(workDone.await(3, TimeUnit.SECONDS));
    // 等处理中提示线程写完（提示在 120ms 时发出，此刻必然已过）
    Thread.sleep(100);
    assertEquals(2, adapter.sent().size());
    assertEquals(InboundMessageService.PROCESSING_REPLY, adapter.sent().get(0).text());
    assertEquals("慢回答", adapter.sent().get(1).text());
  }

  @Test
  @DisplayName("B9: 绑定 Agent 不存在时回复可读说明并落失败执行留痕")
  void missingAgentRepliedAndAudited() {
    when(profileRegistry.get(AGENT)).thenReturn(Optional.empty());

    service.onMessage(p2p("m-7", "hi"), adapter);

    assertEquals(1, adapter.sent().size());
    assertEquals(InboundMessageService.AGENT_UNAVAILABLE_REPLY, adapter.sent().get(0).text());
    verify(executionService).triggerAsync(eq(AGENT), eq("stub"), isNull(), any(Runnable.class));
    verifyNoInteractions(agentService);
  }

  @Test
  @DisplayName("B10: 执行审计以渠道类型为 source、以会话 id 关联")
  void auditCarriesChannelSource() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(eq(session), eq("hi"), anyList())).thenReturn("ok");

    service.onMessage(p2p("m-8", "hi"), adapter);

    verify(executionService)
        .triggerAsync(eq(AGENT), eq("stub"), eq("stub:user-1:" + AGENT), any(Runnable.class));
  }

  @Test
  @DisplayName("回复发送失败只留日志，不炸编排主流程")
  void sendFailureDoesNotPropagate() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(eq(session), eq("hi"), anyList())).thenReturn("ok");
    adapter.failSendsWith(new IllegalStateException("网络故障"));

    service.onMessage(p2p("m-9", "hi"), adapter); // 不抛出即通过
  }

  @Test
  @DisplayName("私聊 /new 清空会话历史，不进 Agent")
  void p2pNewClearsHistoryWithoutAgent() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(sessionManager.clearHistory(session.sessionId())).thenReturn(true);

    service.onMessage(p2p("m-new", "/new"), adapter);

    verify(sessionManager).clearHistory(session.sessionId());
    verifyNoInteractions(agentService);
    assertEquals(1, adapter.sent().size());
    assertEquals(InboundMessageService.NEW_SESSION_REPLY, adapter.sent().get(0).text());
  }

  @Test
  @DisplayName("tryClaim 后 onClaimedMessage 不再二次去重")
  void claimedMessageSkipsSecondDedup() {
    Session session = new Session("stub:user-1:" + AGENT, AGENT);
    when(sessionManager.getOrCreate("stub", "user-1", AGENT)).thenReturn(session);
    when(agentService.process(eq(session), eq("hi"), anyList())).thenReturn("回答");

    assertTrue(service.tryClaim("stub-chan", "m-claimed"));
    service.onClaimedMessage(p2p("m-claimed", "hi"), adapter);

    verify(agentService, times(1)).process(eq(session), eq("hi"), anyList());
    assertEquals(1, adapter.sent().size());
  }
}
