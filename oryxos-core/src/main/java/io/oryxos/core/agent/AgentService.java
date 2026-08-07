package io.oryxos.core.agent;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一次处理的编排者：三种触发源（CLI / Web / 定时）最终都调同一个 {@link #process}。
 *
 * <p>ProfileContext 生命周期在此收口：入口 set、出口 finally clear——即使循环中途抛异常也必须清， 否则复用线程的下一个请求会拿到别人的
 * Profile（单请求测试永远测不出的串号 bug）。
 *
 * <p>并发（review 高危 4）：同一会话（sessionId）的并发请求在此按会话串行化。web 的 send/invoke/trigger 与定时触发
 * 都可能并发操作同一会话（Session 无锁 ArrayList + JpaSessionManager.save 整段覆写），不加锁会 last-write-wins 丢消息。 锁是进程内、按
 * sessionId 隔离——跨会话并行不受影响（宪法 VII 虚拟线程并发仍成立）。进入锁后必须重读最新快照；保存时再由 SessionManager 做条件更新， 防止跨进程旧快照静默覆盖。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "profileRegistry 是 Spring 注入的单例注册表，三种触发源共享同一引用正是意图（29 节起可运行时增删，必须同一份）。")
public class AgentService {

  private static final int MEMORY_LINE_MAX = 200;

  /** 会话 id → 该会话的串行锁。会话数是有限的（channel:user:profile 三元组），增长有界，可接受。 */
  private final ConcurrentMap<String, Lock> sessionLocks = new ConcurrentHashMap<>();

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionManager sessionManager;
  private final MemoryService memoryService;

  public AgentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      MemoryService memoryService) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
    this.memoryService = memoryService;
  }

  public String process(Session session, String userMessage) {
    // 同一会话的读写整段互斥；sessionId 理论上永不为 null（来自 SessionManager），mock 场景兜底防 NPE
    String sessionKey =
        session.sessionId() == null ? profileNameOrFallback(session) : session.sessionId();
    Lock lock = sessionLocks.computeIfAbsent(sessionKey, id -> new ReentrantLock());
    lock.lock();
    try {
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
      ProfileContext.set(profile); // 工具执行时靠它知道"当前是哪个 Agent"
      try {
        String reply = reActLoop.run(activeSession, userMessage, profile);
        // 达到最大迭代上限时 ReAct 返回占位文本（不抛异常），这里检测并转为异常，
        // 让 triggerAsync 把执行记成失败状态（否则前端显示"执行成功"——错误引导用户）
        boolean exhausted = ReActLoop.MAX_ITERATIONS_REPLY.equals(reply);
        activeSession.retainRecentTurns(profile.settings().maxHistoryTurns());
        // 无论正常结束还是迭代耗尽都保存现场；条件更新确保跨进程旧快照不会覆盖新历史。
        sessionManager.saveIfUnchanged(activeSession, expectedMessages);
        if (exhausted) {
          throw new AgentMaxIterationsExceededException(reply);
        }
        recordTrigger(profile.name(), userMessage, reply); // 正常完成才记运行足迹
        return reply;
      } finally {
        ProfileContext.clear(); // 虚拟线程每请求独立，用完必须清
      }
    } finally {
      lock.unlock(); // 无论成功失败必须放锁，否则该会话永久卡死
    }
  }

  private static String profileNameOrFallback(Session session) {
    String name = session.profileName();
    return name == null ? "(null-session)" : name;
  }

  /** 每次触发都往这个 Agent 的记忆归档区记一条运行足迹（这个 Agent 干过什么，事后可回看）。 */
  private void recordTrigger(String agentName, String userMessage, String reply) {
    String line = "触发「" + oneLine(userMessage) + "」⇒ " + oneLine(reply);
    // remember 靠 ToolExecutionContext 定位 Agent（同工具写记忆的路径）：读写路径外要自己置入再清除
    ToolExecutionContext.setAgentName(agentName);
    try {
      memoryService.remember(line, MemoryScope.ARCHIVAL);
    } finally {
      ToolExecutionContext.clear();
    }
  }

  /** 记忆是逐行存的：把多行压成一行、超长截断，避免撑坏归档区的行结构。 */
  private static String oneLine(String text) {
    if (text == null || text.isBlank()) {
      return "（空）";
    }
    String flat = text.replaceAll("\\s+", " ").strip();
    return flat.length() > MEMORY_LINE_MAX ? flat.substring(0, MEMORY_LINE_MAX) + "…" : flat;
  }
}
