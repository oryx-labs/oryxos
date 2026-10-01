package io.oryxos.core.agent;

import io.oryxos.core.capability.CapabilityAssembly;
import io.oryxos.core.capability.CapabilityAssemblyContext;
import io.oryxos.core.capability.CapabilityRef;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一次处理的编排者：有状态触发源（CLI / Web 会话 / 定时 / 管理台）走 {@link #process}；无状态 Web invoke 走 {@link
 * #processStateless}。
 *
 * <p>ProfileContext 生命周期在此收口：入口 set、出口 finally clear——即使循环中途抛异常也必须清， 否则复用线程的下一个请求会拿到别人的
 * Profile（单请求测试永远测不出的串号 bug）。
 *
 * <p>并发（review 高危 4）：同一会话（sessionId）的并发请求在此按会话串行化。web 的 send/invoke/trigger 与定时触发
 * 都可能并发操作同一会话（Session 无锁 ArrayList + JpaSessionManager.save 整段覆写），不加锁会 last-write-wins 丢消息。 锁是进程内、按
 * sessionId 隔离——跨会话并行不受影响（宪法 VII 虚拟线程并发仍成立）。进入锁后必须重读最新快照；保存时再由 SessionManager 做条件更新， 防止跨进程旧快照静默覆盖。
 *
 * <p>不再把「触发问答摘要」自动写入归档记忆：失败回答会带着问句原文进语义召回，轻量模型照抄形成失败自我固化（issue #206）。 执行留痕走 {@code
 * tool_invocations}/{@code agent_executions}；真正要沉淀的事实由 Agent 显式 {@code save_memory}。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "profileRegistry 是 Spring 注入的单例注册表，三种触发源共享同一引用正是意图（29 节起可运行时增删，必须同一份）。")
public class AgentService {

  private static final String STATELESS_EXECUTION_TAG = "invoke-exec";

  /** 会话 id → 该会话的串行锁。会话数是有限的（channel:user:profile 三元组），增长有界，可接受。 */
  private final ConcurrentMap<String, Lock> sessionLocks = new ConcurrentHashMap<>();

  /** 039：turn 根 span 补记（默认 NOOP 零开销；OTel 实现由装配层注入）。 */
  private volatile io.oryxos.core.metrics.SpanRecorder spanRecorder =
      io.oryxos.core.metrics.SpanRecorder.NOOP;

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionManager sessionManager;
  private final io.oryxos.core.cluster.TurnCoordinator turnCoordinator;

  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    // 旧构造委托 NOOP（单机档语义）：既有测试与调用点零破坏
    this(profileRegistry, reActLoop, sessionManager, io.oryxos.core.cluster.TurnCoordinator.NOOP);
  }

  /** 026：多副本档注入 DbTurnCoordinator——在进程内会话锁之内叠加跨副本轮次互斥。 */
  public AgentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      io.oryxos.core.cluster.TurnCoordinator turnCoordinator) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
    this.turnCoordinator = turnCoordinator;
  }

  public String process(Session session, String userMessage) {
    return process(session, userMessage, List.of(), StreamListener.NOOP);
  }

  /** 带入站 media（图片 URL/本地路径）的会话处理。 */
  public String process(Session session, String userMessage, List<Message.MediaPart> media) {
    return process(session, userMessage, media, StreamListener.NOOP);
  }

  /** 带流式观察者的会话处理（019）：锁与 ProfileContext 语义不变，listener 只是透传给 ReActLoop。 */
  public String process(Session session, String userMessage, StreamListener listener) {
    return process(session, userMessage, List.of(), listener);
  }

  /** 带 media + 流式观察者的会话处理。 */
  public String process(
      Session session, String userMessage, List<Message.MediaPart> media, StreamListener listener) {
    // 同一会话的读写整段互斥；sessionId 理论上永不为 null（来自 SessionManager），mock 场景兜底防 NPE
    String sessionKey =
        session.sessionId() == null ? profileNameOrFallback(session) : session.sessionId();
    Lock lock = sessionLocks.computeIfAbsent(sessionKey, id -> new ReentrantLock());
    lock.lock();
    // 026：进程内锁之内叠加跨副本轮次租约（单机档 NOOP 零开销）——认领「正在执行的一轮」，
    // 同会话后到消息跨副本排队等待，与单机排队语义等价
    io.oryxos.core.cluster.TurnLease turnLease = null;
    // 021：兜底开启 trace（controller 先开的场景复用同一 ID，owner=false 不清外层）；
    // 全部触发源（CLI/定时/飞书/REST）经此收口，本轮所有审计落库与日志自动携带同一 traceId
    try (TraceContext.Scope traceScope = TraceContext.openIfAbsent()) {
      // 039：turn 根 span 与本 Scope 同址同源（traceId/起止时间与审计一致），在执行段 finally 补记；
      // 未进入执行段的失败（租约等待超时等）不产生 turn span——没有开轮就没有轮 span
      long turnStartedAt = System.currentTimeMillis();
      boolean turnSuccess = false;
      turnLease = turnCoordinator.acquire(sessionKey);
      Long executionId = ExecutionContext.currentId();
      if (executionId != null) {
        turnLease.attachExecution(executionId); // 026：悬空轮失败留痕的关联键
      }
      // Controller / Channel 在进入本锁前已拿到 Session；等待锁期间它可能过期，因此必须在锁内重读。
      Session activeSession = sessionManager.get(sessionKey).orElse(session);
      List<Message> expectedMessages = activeSession.messages();
      Profile profile =
          profileRegistry
              .get(activeSession.profileName())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Session 引用的 Profile 不存在: " + activeSession.profileName()));
      RunOutputContext.Scope outputScope = RunOutputContext.open(profile.name());
      ProfileContext.set(profile); // 工具执行时靠它知道"当前是哪个 Agent"
      try {
        List<Message.MediaPart> parts = media == null ? List.of() : media;
        // 无 media 且 NOOP：走三参原入口，保持对 ReActLoop 的既有交互契约（现存测试按三参 stub/verify）
        String reply;
        if (parts.isEmpty() && listener == StreamListener.NOOP) {
          reply = reActLoop.run(activeSession, userMessage, profile);
        } else {
          reply = reActLoop.run(activeSession, userMessage, parts, profile, listener);
        }
        // 达到最大迭代上限时 ReAct 返回占位文本（不抛异常），这里检测并转为异常，
        // 让 triggerAsync 把执行记成失败状态（否则前端显示"执行成功"——错误引导用户）
        boolean exhausted = ReActLoop.MAX_ITERATIONS_REPLY.equals(reply);
        activeSession.retainRecentTurns(profile.settings().maxHistoryTurns());
        // 026 fencing 硬闸：租约被回收（假死/超长轮被接管）绝不写回——会话历史以接管方为准
        if (!turnLease.stillHeld()) {
          throw new io.oryxos.core.cluster.TurnFencedException(sessionKey);
        }
        // 无论正常结束还是迭代耗尽都保存现场；条件更新确保跨进程旧快照不会覆盖新历史。
        sessionManager.saveIfUnchanged(activeSession, expectedMessages);
        if (exhausted) {
          throw new AgentMaxIterationsExceededException(maxIterationsMessage(activeSession, reply));
        }
        turnSuccess = true;
        return reply;
      } finally {
        outputScope.close();
        ProfileContext.clear(); // 虚拟线程每请求独立，用完必须清
        spanRecorder.recordTurnSpan(
            traceScope.traceId(),
            session.profileName(),
            channelOf(sessionKey),
            turnSuccess,
            turnStartedAt,
            System.currentTimeMillis() - turnStartedAt);
      }
    } finally {
      if (turnLease != null) {
        turnCoordinator.release(sessionKey, turnLease); // 先释跨副本租约
      }
      lock.unlock(); // 无论成功失败必须放锁，否则该会话永久卡死
    }
  }

  /**
   * 无状态调用：使用带唯一执行标识的内存 Session，不创建或更新持久会话。
   *
   * <p>单次请求内的 ReAct 多轮仍完整执行；审计沿用 Session 标识关联到本次执行，请求结束后临时消息丢弃。
   */
  public String processStateless(String agentName, String userMessage) {
    return processStateless(
        agentName, userMessage, STATELESS_EXECUTION_TAG + ":" + UUID.randomUUID());
  }

  /** Direction C：无状态调用 + 能力装配覆盖（Flow TEAM_TASK / AGENT 节点 capabilities）。空列表等价于无覆盖。 */
  public String processStateless(
      String agentName, String userMessage, java.util.List<CapabilityRef> capabilities) {
    return processStateless(
        agentName,
        userMessage,
        java.util.List.of(),
        STATELESS_EXECUTION_TAG + ":" + UUID.randomUUID(),
        StreamListener.NOOP,
        capabilities);
  }

  /** 带流式观察者的无状态调用（019）：临时会话标识自动生成，其余语义同上。 */
  public String processStateless(String agentName, String userMessage, StreamListener listener) {
    return processStateless(
        agentName, userMessage, STATELESS_EXECUTION_TAG + ":" + UUID.randomUUID(), listener);
  }

  /**
   * 无状态调用（带完整临时会话标识）：{@code statelessSessionId} 由调用方生成并同时用于 {@code agent_executions} 关联，使审计三表可按同一
   * id 串联、触发渠道可辨（017 FR-014：群聊问答传 "feishu-group:&lt;uuid&gt;" 形态， 审计可按 {@code session_id LIKE
   * 'feishu%'} 查询）。仍不创建持久会话。
   */
  public String processStateless(String agentName, String userMessage, String statelessSessionId) {
    return processStateless(
        agentName, userMessage, List.of(), statelessSessionId, StreamListener.NOOP);
  }

  /** 无状态调用 + media（群聊入站图片等）。 */
  public String processStateless(
      String agentName,
      String userMessage,
      List<Message.MediaPart> media,
      String statelessSessionId) {
    return processStateless(agentName, userMessage, media, statelessSessionId, StreamListener.NOOP);
  }

  /** 无状态调用全参形态（019）：显式会话标识 + 流式观察者。 */
  public String processStateless(
      String agentName, String userMessage, String statelessSessionId, StreamListener listener) {
    return processStateless(agentName, userMessage, List.of(), statelessSessionId, listener);
  }

  /** 无状态调用全参形态：会话标识 + media + 流式观察者。 */
  public String processStateless(
      String agentName,
      String userMessage,
      List<Message.MediaPart> media,
      String statelessSessionId,
      StreamListener listener) {
    return processStateless(
        agentName, userMessage, media, statelessSessionId, listener, java.util.List.of());
  }

  /** 无状态调用全参 + Direction C 能力装配。 */
  public String processStateless(
      String agentName,
      String userMessage,
      List<Message.MediaPart> media,
      String statelessSessionId,
      StreamListener listener,
      List<CapabilityRef> capabilities) {
    Profile profile =
        profileRegistry
            .get(agentName)
            .orElseThrow(() -> new IllegalStateException("Agent 不存在: " + agentName));
    CapabilityAssembly assembly = CapabilityAssembly.from(capabilities);
    profile = profile.withCapabilityAssembly(assembly);
    Session session = new Session(statelessSessionId, profile.name());
    RunOutputContext.Scope outputScope = RunOutputContext.open(profile.name());
    ProfileContext.set(profile);
    CapabilityAssemblyContext.set(assembly);
    // 021：同 process——兜底开启 trace，已开启则复用
    try (TraceContext.Scope traceScope = TraceContext.openIfAbsent()) {
      // 039：无状态一轮同样补记 turn 根 span（invoke/群聊问答路径；真机走查实测缺口）
      long turnStartedAt = System.currentTimeMillis();
      boolean turnSuccess = false;
      try {
        List<Message.MediaPart> parts = media == null ? List.of() : media;
        String reply;
        if (parts.isEmpty() && listener == StreamListener.NOOP) {
          reply = reActLoop.run(session, userMessage, profile);
        } else {
          reply = reActLoop.run(session, userMessage, parts, profile, listener);
        }
        if (ReActLoop.MAX_ITERATIONS_REPLY.equals(reply)) {
          throw new AgentMaxIterationsExceededException(maxIterationsMessage(session, reply));
        }
        turnSuccess = true;
        return reply;
      } finally {
        spanRecorder.recordTurnSpan(
            traceScope.traceId(),
            agentName,
            channelOf(statelessSessionId),
            turnSuccess,
            turnStartedAt,
            System.currentTimeMillis() - turnStartedAt);
      }
    } finally {
      outputScope.close();
      ProfileContext.clear();
      CapabilityAssemblyContext.clear();
    }
  }

  /**
   * 达到最大轮数的失败信息：消息始终以哨兵串 {@code sentinel} 打头（仍含「达到最大轮数」， {@code
   * AgentExecutionService.isMaxIterationsError} 据此归类为 MAX_ITERATIONS）；若 session 末条是 ReActLoop
   * 收尾追加的「无工具调用 assistant 文本」，则把它拼在后面，让用户看到尽力而为的答复。 未开启收尾时末条不满足条件，退回纯哨兵串——与旧行为完全一致。
   */
  private static String maxIterationsMessage(Session session, String sentinel) {
    List<Message> msgs = session.messages();
    if (!msgs.isEmpty()) {
      Message last = msgs.get(msgs.size() - 1);
      if (Message.ROLE_ASSISTANT.equals(last.role()) && last.toolCalls().isEmpty()) {
        String text = last.content() == null ? "" : last.content().strip();
        if (!text.isEmpty()) {
          return sentinel + "\n\n" + text;
        }
      }
    }
    return sentinel;
  }

  private static String profileNameOrFallback(Session session) {
    String name = session.profileName();
    return name == null ? "(null-session)" : name;
  }

  public void setSpanRecorder(io.oryxos.core.metrics.SpanRecorder spanRecorder) {
    this.spanRecorder =
        spanRecorder == null ? io.oryxos.core.metrics.SpanRecorder.NOOP : spanRecorder;
  }

  /** 039：sessionId 惯例为「channel:...」联合键，取首段作 span 的渠道属性（无冒号即整串）。 */
  private static String channelOf(String sessionKey) {
    if (sessionKey == null) {
      return "unknown";
    }
    int idx = sessionKey.indexOf(':');
    return idx > 0 ? sessionKey.substring(0, idx) : sessionKey;
  }
}
